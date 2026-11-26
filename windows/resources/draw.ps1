# draw: a picture made from words, with free services that need no account or a key.
# The quick one (Pollinations) is asked first; when it does not draw, the slower one
# (AI Horde, volunteers' computers) is asked. It follows the draw command of the
# Android app (android/assets/guest/kit). Pictures are kept in the Pictures folder
# of the user's profile. Called through draw.cmd, which works in every shell.

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

# --- the two free services; the environment may name another host (the tests do)
$Pollinations = $env:TERMANT_DRAW_POLLINATIONS
if (-not $Pollinations) { $Pollinations = 'https://image.pollinations.ai' }
$Pollinations = $Pollinations.TrimEnd('/')
$Horde = $env:TERMANT_DRAW_HORDE
if (-not $Horde) { $Horde = 'https://aihorde.net/api/v2' }
$Horde = $Horde.TrimEnd('/')
$HordeAgent = 'termant:1:https://github.com/siddhantakakhael-cyber/Termant'
# general models of the AI Horde, asked for first; when no worker has them, any model draws
$HordeModels = @('Dreamshaper', "ICBINP - I Can't Believe It's Not Photography", 'stable_diffusion')
# {shape: (size at the first service, size at the second)}; the second gives at most 576 a side for nothing
$DrawSizes = @{
  square = @(@(768, 768), @(512, 512))
  wide   = @(@(768, 432), @(576, 320))
  tall   = @(@(432, 768), @(320, 576))
}

function Fail([string]$message) { [Console]::Error.WriteLine($message); exit 1 }
function Say([string]$message) { [Console]::Error.WriteLine($message) }
function Size-Text([double]$n) {
  foreach ($u in @('bytes', 'kB', 'MB', 'GB')) {
    if ($n -lt 1000 -or $u -eq 'GB') {
      if ($u -eq 'bytes') { return ('{0} bytes' -f [int]$n) }
      return ('{0:0.0} {1}' -f $n, $u)
    }
    $n = $n / 1000.0
  }
}
function One-Line([string]$text, [int]$limit = 200) {
  $t = ([string]$text -replace '\s+', ' ').Trim()
  if ($t.Length -le $limit) { return $t }
  return $t.Substring(0, $limit - 1) + '...'
}
function Env-Number([string]$name, [double]$default) {
  # the $env: scope wants a literal name; a name kept in a variable is asked of the process
  $value = [Environment]::GetEnvironmentVariable($name)
  if ($value) {
    $number = 0.0
    if ([double]::TryParse($value, [ref]$number)) { return $number }
  }
  return $default
}
function Now-Seconds() { [double][DateTimeOffset]::UtcNow.ToUnixTimeSeconds() }

function Data-Dir {
  if ($env:TERMANT_DATA) { return $env:TERMANT_DATA }
  return (Join-Path $env:APPDATA 'Termant')
}
$DataDir = Data-Dir
function Load-State {
  try { return (Get-Content -Raw -LiteralPath (Join-Path $DataDir 'draw.json') | ConvertFrom-Json) } catch { return $null }
}
function Save-State($state) {
  New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
  $text = $state | ConvertTo-Json -Depth 5 -Compress
  $temp = Join-Path $DataDir 'draw.json.new'
  [System.IO.File]::WriteAllText($temp, $text, (New-Object System.Text.UTF8Encoding($false)))
  Move-Item -LiteralPath $temp -Destination (Join-Path $DataDir 'draw.json') -Force
}
function State-Value($state, [string]$name) {
  if ($state -and $state.PSObject.Properties[$name]) { return $state.$name }
  return $null
}
function State-Set($state, [string]$name, $value) {
  if (-not $state) { $state = [PSCustomObject]@{} }
  $state | Add-Member -NotePropertyName $name -NotePropertyValue $value -Force
  return $state
}

# --- one http request; @{ code = status (0 when not reached); body = bytes; retry = header or '' }
$script:http = [System.Net.Http.HttpClient]::new()
$script:http.Timeout = [TimeSpan]::FromSeconds(300)
function Web-Request([string]$method, [string]$url, $bodyObject, [hashtable]$headers, [int]$seconds) {
  $cts = [System.Threading.CancellationTokenSource]::new()
  $cts.CancelAfter($seconds * 1000)
  $request = [System.Net.Http.HttpRequestMessage]::new($method, $url)
  if ($null -ne $bodyObject) {
    $request.Content = [System.Net.Http.StringContent]::new(($bodyObject | ConvertTo-Json -Depth 10 -Compress), [Text.Encoding]::UTF8, 'application/json')
  }
  foreach ($key in $headers.Keys) { $request.Headers.TryAddWithoutValidation($key, [string]$headers[$key]) | Out-Null }
  $response = $null
  try {
    $response = $script:http.SendAsync($request, $cts.Token).GetAwaiter().GetResult()
    $code = [int]$response.StatusCode
    $body = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $retry = ''
    $values = $null
    if ($response.Headers.TryGetValues('Retry-After', [ref]$values)) { $retry = ($values | Select-Object -First 1) }
    return @{ code = $code; body = $body; retry = [string]$retry }
  } catch [System.Net.Http.HttpRequestException] {
    return @{ code = 0; body = [Text.Encoding]::UTF8.GetBytes($_.Message); retry = '' }
  } catch [System.Threading.Tasks.TaskCanceledException] {
    return @{ code = 0; body = [Text.Encoding]::UTF8.GetBytes('timed out'); retry = '' }
  } catch {
    return @{ code = 0; body = [Text.Encoding]::UTF8.GetBytes($_.Exception.Message); retry = '' }
  } finally {
    if ($response) { $response.Dispose() }
    $request.Dispose()
    $cts.Dispose()
  }
}
function Why([int]$code, [string]$said) {
  $said = One-Line $said 160
  if ($code -eq 0) {
    if ($said) { return "could not be reached ($said)" }
    return 'could not be reached (no connection)'
  }
  if ($code -eq 402 -or $code -eq 429) { return "busy: it draws for one address only now and then (HTTP $code)" }
  if ($said -and -not $said.StartsWith('<')) { return 'HTTP {0}: {1}' -f $code, $said }
  return "HTTP $code"
}
function Picture-Kind([byte[]]$data) {
  if ($data.Length -ge 3 -and $data[0] -eq 0xFF -and $data[1] -eq 0xD8 -and $data[2] -eq 0xFF) { return 'jpg' }
  if ($data.Length -ge 8 -and $data[0] -eq 0x89 -and $data[1] -eq 0x50 -and $data[2] -eq 0x4E -and $data[3] -eq 0x47) { return 'png' }
  if ($data.Length -ge 12 -and [Text.Encoding]::ASCII.GetString($data, 0, 4) -eq 'RIFF' -and [Text.Encoding]::ASCII.GetString($data, 8, 4) -eq 'WEBP') { return 'webp' }
  if ($data.Length -ge 3 -and $data[0] -eq 0x47 -and $data[1] -eq 0x49 -and $data[2] -eq 0x46) { return 'gif' }
  return $null
}
function Picture-Size([string]$file) {
  try {
    Add-Type -AssemblyName System.Drawing 2>$null
    $image = [System.Drawing.Image]::FromFile((Resolve-Path -LiteralPath $file).ProviderPath)
    try { return @($image.Width, $image.Height) } finally { $image.Dispose() }
  } catch { return $null }
}

# --- the quick service: one address, one picture at a time
function Draw-Pollinations([string]$prompt, [array]$size, [int]$seed) {
  $url = "$Pollinations/prompt/$([Uri]::EscapeDataString($prompt))?width=$($size[0])&height=$($size[1])&seed=$seed&nologo=true"
  $state = Load-State
  $asked = 0.0
  $a = State-Value $state 'asked'
  if ($null -ne $a) { $asked = [double]$a }
  $gap = (Env-Number 'TERMANT_DRAW_GAP' 45) - (Now-Seconds - $asked)
  if ($gap -gt 0 -and $gap -le 60) {
    Say ("Waiting {0} s (the free service draws one picture at a time)..." -f [int][Math]::Round($gap))
    Start-Sleep -Seconds ([int][Math]::Round($gap))
  }
  for ($attempt = 1; $attempt -le 2; $attempt++) {
    Save-State (State-Set (Load-State) 'asked' (Now-Seconds))
    $r = Web-Request 'GET' $url $null @{ 'User-Agent' = 'Termant'; 'Accept' = 'image/*' } 180
    if ($r.code -eq 200 -and (Picture-Kind $r.body)) { return @{ ok = $true; data = $r.body } }
    $wait = 0.0
    [void][double]::TryParse($r.retry, [ref]$wait)
    if ($attempt -eq 1 -and ($r.code -eq 402 -or $r.code -eq 429) -and $r.retry -ne '' -and $wait -le 60) {
      Say ("The service asks to wait {0} s..." -f [int][Math]::Round($wait))
      Start-Sleep -Seconds ($wait + 1)
      continue
    }
    if ($r.code -eq 200) { return @{ ok = $false; reason = 'gave no picture' } }
    return @{ ok = $false; reason = (Why $r.code ([Text.Encoding]::UTF8.GetString($r.body))) }
  }
  return @{ ok = $false; reason = 'gave no picture' }
}

# --- the slow service: a queue of volunteers' computers; a minute or more
function Draw-Horde([string]$prompt, [array]$size, [int]$seed) {
  $heads = @{ 'apikey' = '0000000000'; 'Client-Agent' = $HordeAgent; 'User-Agent' = 'Termant' }
  $limit = Env-Number 'TERMANT_DRAW_WAIT' 300
  $pause = Env-Number 'TERMANT_DRAW_POLL' 4
  $last = 'gave no picture'
  foreach ($models in @(@($HordeModels), @())) {
    $asked = [ordered]@{
      prompt  = $prompt + ' ### low quality, blurry, deformed, watermark, text'
      params  = [ordered]@{ width = [int]$size[0]; height = [int]$size[1]; steps = 25; n = 1; sampler_name = 'k_euler_a'; cfg_scale = 7; seed = [string]$seed }
      nsfw    = $false
      censor_nsfw = $true
      trusted_workers = $false
      slow_workers = $true
      r2      = $true
      shared  = $false
      models  = $models
    }
    $r = Web-Request 'POST' "$Horde/generate/async" $asked $heads 60
    $job = $null
    if ($r.code -eq 200 -or $r.code -eq 202) {
      try { $job = ([Text.Encoding]::UTF8.GetString($r.body) | ConvertFrom-Json).id } catch { $job = $null }
    }
    if (-not $job) { return @{ ok = $false; reason = (Why $r.code ([Text.Encoding]::UTF8.GetString($r.body))) } }
    $began = Now-Seconds
    $told = 0.0
    $possible = $true
    $done = $false
    while ((Now-Seconds - $began) -lt $limit) {
      Start-Sleep -Seconds ([int]$pause)
      $r = Web-Request 'GET' "$Horde/generate/check/$job" $null $heads 30
      $st = $null
      if ($r.code -eq 200) { try { $st = [Text.Encoding]::UTF8.GetString($r.body) | ConvertFrom-Json } catch { $st = $null } }
      if ($st -and $st.faulted) { $last = 'its worker failed'; break }
      if ($st -and $st.done) { $done = $true; break }
      if ($st -and ($st.is_possible -eq $false)) { $possible = $false; break }
      if ((Now-Seconds - $told) -gt 20 -and $st) {
        $told = Now-Seconds
        $pos = State-Value $st 'queue_position'
        if ($null -eq $pos) { $pos = '?' }
        $wt = State-Value $st 'wait_time'
        if ($null -eq $wt) { $wt = '?' }
        Say ("In the queue: place {0}, about {1} s more..." -f $pos, $wt)
      }
    }
    if (-not $done) {
      [void](Web-Request 'DELETE' "$Horde/generate/status/$job" $null $heads 30)
      if (-not $possible -and $models.Count) { continue }
      if ($possible -and $last -eq 'gave no picture') { $last = "was not ready after {0} s (its queue is long right now)" -f [int]$limit }
      return @{ ok = $false; reason = $last }
    }
    $r = Web-Request 'GET' "$Horde/generate/status/$job" $null $heads 60
    $made = $null
    if ($r.code -eq 200) {
      try {
        $generations = ([Text.Encoding]::UTF8.GetString($r.body) | ConvertFrom-Json).generations
        if ($generations) { $made = $generations[0] }
      } catch { $made = $null }
    }
    if ($made -and $made.censored) { return @{ ok = $false; reason = 'held the picture back as not safe to show' } }
    $img = ''
    if ($made) { $img = [string]$made.img }
    if ($img.StartsWith('http')) {
      $r2 = Web-Request 'GET' $img $null @{ 'User-Agent' = 'Termant' } 120
      if ($r2.code -ne 200) {
        return @{ ok = $false; reason = "made a picture that could not be fetched ($(Why $r2.code ([Text.Encoding]::UTF8.GetString($r2.body))))" }
      }
      $data = $r2.body
    } else {
      try { $data = [Convert]::FromBase64String($img) } catch { $data = [byte[]]@() }
    }
    if (Picture-Kind $data) { return @{ ok = $true; data = $data } }
    if ($r.code -ne 200) { return @{ ok = $false; reason = (Why $r.code ([Text.Encoding]::UTF8.GetString($r.body))) } }
    return @{ ok = $false; reason = 'gave no picture' }
  }
  return @{ ok = $false; reason = $last }
}

# --- the command itself
if ($args.Count -eq 0 -or $args[0] -eq 'help' -or $args[0] -eq '--help' -or $args[0] -eq '-h') {
  Write-Output @"
draw: a picture made from words (free services, no account or key)
  draw "WHAT TO DRAW"           a square picture; say it in English, with details:
                                the thing, the place, the light, the style (photo, painting, cartoon)
  draw --wide "..."             wider than high          draw --tall "..."    higher than wide
  draw --out FILE "..."         save it under this name  draw --seed 7 "..."   the same number draws the same again
  draw --slow "..."             ask the slow service first: no logo in the corner, a minute or more
  draw last                     where the newest picture is
Two services draw. Pollinations is quick (a few seconds, 768 a side, its logo in a corner) but
draws for one address only every 45 seconds; draw waits for it up to a minute. When it does
not draw, AI Horde is asked: volunteers' computers, a minute or more, 512 a side, no logo.
Pictures are kept in the Pictures folder of the user's profile.
"@
  exit 0
}
if ($args[0] -eq 'last') {
  $newest = State-Value (Load-State) 'last'
  if (-not $newest -or -not (Test-Path -LiteralPath ([string]$newest) -PathType Leaf)) { Fail 'draw: no picture has been made yet' }
  Write-Output ([string]$newest)
  exit 0
}

$shape = 'square'
$target = $null
$seed = $null
$slow = $false
$words = @()
for ($i = 0; $i -lt $args.Count; $i++) {
  $a = $args[$i]
  if ($a -eq '--wide' -or $a -eq '--tall' -or $a -eq '--square') { $shape = $a.Substring(2) }
  elseif ($a -eq '--slow') { $slow = $true }
  elseif (($a -eq '--out' -or $a -eq '--seed') -and ($i + 1) -lt $args.Count) {
    if ($a -eq '--out') { $target = $args[$i + 1] }
    else {
      if ($args[$i + 1] -notmatch '^-?\d+$') { Fail 'draw: --seed takes a number' }
      $seed = [int]$args[$i + 1]
    }
    $i++
  }
  elseif ($a.StartsWith('--')) { Fail "draw: there is no $a (draw help)" }
  else { $words += $a }
}
$prompt = One-Line ($words -join ' ') 900
if ($prompt.Length -lt 3) { Fail 'usage: draw "what to draw"   (draw help)' }
if ($null -eq $seed) { $seed = Get-Random -Minimum 0 -Maximum 2000000000 }

$reasons = @()
$data = $null
$by = ''
$services = @(@('Pollinations', $DrawSizes.$shape[0]), @('AI Horde', $DrawSizes.$shape[1]))
if ($slow) { $services = @($services[1], $services[0]); Say 'Asking AI Horde, which takes a minute or more...' }
foreach ($service in $services) {
  $name = $service[0]
  $size = $service[1]
  if ($reasons.Count) {
    $prev = [string]$reasons[$reasons.Count - 1]
    if ($prev -match ': ') { $prev = $prev.Split(': ', 2)[1] }
    $extra = ''
    if ($name -eq 'AI Horde') { $extra = ', which takes a minute or more' }
    Say ("{0} did not draw ({1}). Asking {2}{3}..." -f $by, $prev, $name, $extra)
  }
  $by = $name
  if ($name -eq 'Pollinations') { $r = Draw-Pollinations $prompt $size $seed } else { $r = Draw-Horde $prompt $size $seed }
  if ($r.ok) { $data = $r.data; break }
  $reasons += ("{0}: {1}" -f $name, $r.reason)
}
if (-not $data) {
  # every reason says the service could not be reached: probably no internet
  $unreachable = @($reasons | Where-Object { $_ -match 'could not be reached' })
  $hint = 'Both are free services that are sometimes busy; try again in a few minutes.'
  if ($reasons.Count -and $unreachable.Count -eq $reasons.Count) { $hint = 'Is the internet on?' }
  Fail ("draw: no picture this time. {0}. {1}. {2} Tell the user plainly that no picture was made; do not describe one." -f $reasons[0], $reasons[$reasons.Count - 1], $hint)
}

$ending = Picture-Kind $data
if ($target) {
  $target = [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $target))
  $target = [System.IO.Path]::Combine([System.IO.Path]::GetDirectoryName($target), ([System.IO.Path]::GetFileNameWithoutExtension($target) + '.' + $ending))
} else {
  $slug = ($prompt.ToLower() -replace '[^a-z0-9]+', '-').Trim('-')
  if ($slug.Length -gt 40) { $slug = $slug.Substring(0, 40).Trim('-') }
  if (-not $slug) { $slug = 'picture' }
  $target = Join-Path (Join-Path $env:USERPROFILE 'pictures') ("{0}-{1}.{2}" -f $slug, (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss'), $ending)
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
[System.IO.File]::WriteAllBytes($target, $data)
$dimensions = Picture-Size $target
$sizePart = ''
if ($dimensions) { $sizePart = '{0}x{1}, ' -f $dimensions[0], $dimensions[1] }
Write-Output ("Picture saved: {0} ({1}{2}, drawn by {3})" -f $target, $sizePart, (Size-Text $data.Length), $by)

$state = State-Set (Load-State) 'last' $target
Save-State $state
if ($env:TERMANT_CHAT) {
  # The app empties this list before each message and reads it when the reply is done:
  # these pictures are shown under the reply. It reads pictures in the home folder only.
  $shown = $target
  $homeDir = $env:USERPROFILE
  if (-not $shown.StartsWith($homeDir + [System.IO.Path]::DirectorySeparatorChar)) {
    $shown = Join-Path (Join-Path $homeDir 'pictures') (Split-Path -Leaf $target)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $shown) | Out-Null
    Copy-Item -LiteralPath $target -Destination $shown -Force
  }
  $cache = $env:XDG_CACHE_HOME
  if (-not $cache) { $cache = Join-Path $DataDir 'agent' 'cache' }
  try {
    New-Item -ItemType Directory -Force -Path $cache | Out-Null
    [System.IO.File]::AppendAllText((Join-Path $cache 'drawn.txt'), ($shown + [Environment]::NewLine), (New-Object System.Text.UTF8Encoding($false)))
    Write-Output 'The chat shows this picture under your answer by itself: do not paste its path or a link, just say in one line what you drew.'
  } catch { }
}

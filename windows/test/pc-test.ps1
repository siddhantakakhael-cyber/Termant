# Tests the `pc` command on a real Windows desktop (run by the GitHub workflow).
#   powershell -ExecutionPolicy Bypass -File windows\test\pc-test.ps1 <folder for the results>
param([string]$Out = 'pc-test-out')
$ErrorActionPreference = 'Continue'
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$Out = (Resolve-Path $Out).Path
$pc = Join-Path $PSScriptRoot '..\resources\pc.cmd'
# no Termant app is running in this test; its folder is outside the results (the reader's 23 MB land there)
$env:TERMANT_DATA = Join-Path ([IO.Path]::GetTempPath()) 'termant-pc-test'
$script:hard = 0; $script:soft = 0
$log = Join-Path $Out 'pc-test.log'

function Run { $o = ((& $pc @args 2>&1 | ForEach-Object { "$_" }) -join "`n").Trim(); return @{ out = $o; code = $LASTEXITCODE } }
function Check($name, $ok, $detail, [switch]$Soft) {
  $word = if ($ok) { 'PASS' } elseif ($Soft) { $script:soft++; 'SOFT-FAIL' } else { $script:hard++; 'FAIL' }
  $line = "$word $name :: $detail"
  Write-Output $line; Add-Content -Path $log -Value $line
}
function Expect($name, $pattern, [switch]$Soft) {
  $r = Run @args
  Check $name (($r.code -eq 0) -and ($r.out -match $pattern)) "exit $($r.code): $($r.out -replace '\s+', ' ')" -Soft:$Soft
  return $r
}

Expect 'help' 'control this PC' help | Out-Null
Expect 'info' 'Processor:' info | Out-Null
Expect 'battery' 'attery' battery | Out-Null
Expect 'wifi' 'Wi-Fi' wifi | Out-Null
Expect 'processes' ' MB' processes | Out-Null
Expect 'apps' '\w' apps -Soft | Out-Null
Expect 'clipboard set' 'Copied' clipboard set 'termant clip 42' | Out-Null
Expect 'clipboard get' 'termant clip 42' clipboard get | Out-Null
$r = Run notify Hello there
Check 'app commands say that the app is not running' (($r.code -ne 0) -and ($r.out -match 'not running')) "exit $($r.code): $($r.out)"
$r = Run key nosuchkey
Check 'an unknown key is refused' (($r.code -ne 0) -and ($r.out -match 'do not know the key')) "exit $($r.code): $($r.out)"

# ---- a real window: open Notepad, bring it to the front, type, read it back, click, close
Expect 'open notepad' 'Opened' open notepad | Out-Null
Start-Sleep -Seconds 4
Expect 'windows lists Notepad' 'Notepad' windows | Out-Null
Expect 'focus' 'In front: .*Notepad' focus Notepad | Out-Null
Start-Sleep -Seconds 1
Expect 'type' 'Typed' type 'hello termant (1+1)' | Out-Null
Start-Sleep -Seconds 1
$screen = Expect 'screen reads the typed text' 'hello termant \(1\+1\)' screen
Add-Content -Path (Join-Path $Out 'screen-notepad.txt') -Value $screen.out
Check 'screen gives click points' ($screen.out -match '@ \d+,\d+') ''
Expect 'screenshot' 'Saved' screenshot (Join-Path $Out 'desktop-notepad.png') | Out-Null
$shot = Get-Item (Join-Path $Out 'desktop-notepad.png') -ErrorAction SilentlyContinue
Check 'the screenshot is a real picture' ($shot -and $shot.Length -gt 5000) "bytes: $($shot.Length)"

Expect 'key combination' 'Pressed' key ctrl+a ctrl+c | Out-Null
Start-Sleep -Milliseconds 500
Expect 'what was typed arrived whole (copied back out)' '^hello termant \(1\+1\)$' clipboard get | Out-Null

# a double click selects the word under the pointer: in a maximised Notepad the first word is at the top left
Run clipboard set nothing-yet | Out-Null
Run key win+up | Out-Null
Start-Sleep -Seconds 1
Expect 'double click' 'Clicked' click 22 58 double | Out-Null
Start-Sleep -Milliseconds 500
Run key ctrl+c | Out-Null
Start-Sleep -Milliseconds 500
Run screenshot (Join-Path $Out 'desktop-double-click.png') | Out-Null
Expect 'the double click selected the word under it' '^hello\s*$' clipboard get -Soft | Out-Null

# ---- reading a picture of the window (Mirage): find the File menu by its writing, click there, read the menu
Run key end | Out-Null
Start-Sleep -Milliseconds 500
$r = Run screen ocr
Add-Content -Path (Join-Path $Out 'screen-ocr.txt') -Value $r.out
Check 'screen ocr reads a picture of the window' (($r.code -eq 0) -and ($r.out -match 'Read from a picture')) "exit $($r.code): $(($r.out -split "`n" | Select-Object -First 4) -join ' | ')"
Check 'the picture gives the window title' ($r.out -match '\[Text\] "[^"]*Notepad') ''
Check 'the picture gives what was typed, letter for letter' ($r.out -match '\[Text\] "hello termant \(1\+1\)"') (($r.out -split "`n" | Where-Object { $_ -match 'termant' }) -join ' | ') -Soft
$m = [regex]::Match($r.out, '\[Text\] "File" @ (\d+),(\d+)')
Check 'the picture gives the File menu and its point' $m.Success (($r.out -split "`n" | Where-Object { $_ -match 'File|Edit' }) -join ' | ') -Soft
if ($m.Success) {
  Run click $m.Groups[1].Value $m.Groups[2].Value | Out-Null
  Start-Sleep -Seconds 1
  Run screenshot (Join-Path $Out 'desktop-menu.png') | Out-Null
  $r = Run screen ocr
  Add-Content -Path (Join-Path $Out 'screen-ocr-menu.txt') -Value $r.out
  $items = @('New', 'Open', 'Save', 'Print', 'Exit') | Where-Object { $r.out -match "\[Text\] `"$_" }
  Check 'a click on that point opened the menu, and the picture gives its entries' ($items.Count -ge 4) "found: $($items -join ', ')" -Soft
  # the first Escape closes the list but leaves the menu bar active; the second gives the keyboard back to the text
  Run key escape escape | Out-Null
  Start-Sleep -Milliseconds 500
}

# ---- what is in a photo and where (Mirage)
# Of answers of Mirage kept in files, look must make exactly the kept lines; the Android app is checked against the same files.
$kept = Join-Path $PSScriptRoot '..\..\android\mirage\look'
$wrong = @()
foreach ($case in (Get-Content (Join-Path $kept 'cases.txt'))) {
  $name, $w, $h = $case -split ' '
  if (-not $name) { continue }
  $r = Run look-answer (Join-Path $kept "$name.json") $w $h "$name.jpg" phone
  $want = ([IO.File]::ReadAllText((Join-Path $kept "$name.txt"), [Text.Encoding]::UTF8)).Trim()
  # letters outside ASCII can come changed through the pipe of this test: each run of them counts as one mark
  $got = ($r.out -replace "`r", '') -replace '[^\x00-\x7F]+', '?'
  if (($r.code -ne 0) -or ($got -cne (($want -replace "`r", '') -replace '[^\x00-\x7F]+', '?'))) {
    $wrong += $name
    Set-Content -Path (Join-Path $Out "look-$name.txt") -Value $r.out
  }
}
Check 'look makes the kept lines of every kept answer of Mirage' ($wrong.Count -eq 0) "different: $($wrong -join ', ')"

# a real photo: a dog in front of a bicycle (from the darknet project's examples, fetched at a fixed revision)
$photo = Join-Path ([IO.Path]::GetTempPath()) 'termant dog.jpg'
try {
  [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
  $ProgressPreference = 'SilentlyContinue'
  Invoke-WebRequest -UseBasicParsing -TimeoutSec 120 -OutFile $photo -Uri 'https://raw.githubusercontent.com/pjreddie/darknet/f6afaabcdf85f77e7aff2ec55c020c0e297c77f9/data/dog.jpg'
} catch { Write-Output "the test photo could not be fetched: $($_.Exception.Message)" }
$fetched = (Test-Path -LiteralPath $photo) -and ((Get-FileHash -LiteralPath $photo -Algorithm SHA256).Hash -eq '5A9522051C3CEC2BBD2F6323FCCBA32E8FBF3DDCC2B3E2FD46B04C720BC6F866')
Check 'the test photo was fetched' $fetched ''
if ($fetched) {
  $r = Run look $photo
  Add-Content -Path (Join-Path $Out 'look-photo.txt') -Value $r.out
  Check 'look names the photo and its size' (($r.code -eq 0) -and ($r.out -match '(?m)^Picture termant dog\.jpg \(768x576\), seen by Mirage in \d+\.\d s\.')) "exit $($r.code): $(($r.out -split "`n" | Select-Object -First 3) -join ' | ')"
  Check 'look finds the dog, on the left of the photo' ($r.out -match '(?m)^- dog, \d+%: left, ') (($r.out -split "`n" | Where-Object { $_ -match '^- ' }) -join ' | ')
  Check 'look finds the bicycle' ($r.out -match '(?m)^- bicycle, ') '' -Soft
  Check 'look tells the scene' ($r.out -match '(?m)^Scene: ') '' -Soft
}
$r = Run look
Add-Content -Path (Join-Path $Out 'look-window.txt') -Value $r.out
Check 'look without a file looks at the window in front' (($r.code -eq 0) -and ($r.out -match '(?m)^The window in front as a picture \(\d+x\d+\), seen by Mirage')) "exit $($r.code): $(($r.out -split "`n" | Select-Object -First 6) -join ' | ')"
$r = Run look (Join-Path $Out 'no-such-photo.jpg')
Check 'look refuses a file that is not there' (($r.code -eq 1) -and ($r.out -match 'no such file')) "exit $($r.code): $($r.out)"
$r = Run look (Join-Path $PSScriptRoot 'pc-test.ps1')
Check 'look refuses a file that is no picture' (($r.code -eq 1) -and ($r.out -match 'not a picture')) "exit $($r.code): $($r.out)"

Expect 'key' 'Pressed' key ctrl+a delete | Out-Null
Start-Sleep -Milliseconds 600
$r = Run screen
Check 'delete emptied the text' (-not ($r.out -match 'hello termant')) ($r.out -replace '\s+', ' ').Substring(0, [Math]::Min(300, ($r.out -replace '\s+', ' ').Length))

Expect 'move' 'Moved' move 200 200 | Out-Null
Expect 'scroll' 'Scrolled' scroll -3 | Out-Null

Expect 'kill' 'Stopped' kill notepad | Out-Null
Start-Sleep -Seconds 1
$r = Run windows
Check 'Notepad is gone' (-not ($r.out -match 'Notepad')) ($r.out -replace '\s+', ' ')

# ---- things a test machine may not have: reported, but they do not fail the test
Expect 'volume set' 'Volume 30' volume 30 -Soft | Out-Null
Expect 'volume get' 'Volume 30' volume -Soft | Out-Null
# ---- Word documents, without Word: the file itself is made, read and changed
$doc = Join-Path $Out 'test letter.docx'
Remove-Item -LiteralPath $doc -ErrorAction SilentlyContinue
Expect 'word new' 'with 2 paragraph' word new $doc 'Dear Asha,\nThe year is 2024.' | Out-Null
$r = Run word new $doc again
Check 'word new does not overwrite' (($r.code -ne 0) -and ($r.out -match 'already there')) "exit $($r.code): $($r.out)"
$r = Run word read $doc
Check 'word read numbers the paragraphs' (($r.code -eq 0) -and ($r.out -match '(?m)^1: Dear Asha,\s*$') -and ($r.out -match '(?m)^2: The year is 2024\.\s*$') -and ($r.out -match '\(2 paragraphs, 2 with text\)')) "exit $($r.code): $($r.out -replace '\s+', ' ')"
Expect 'word add' 'Added 1 paragraph' word add $doc 'Yours, Termant' | Out-Null
Expect 'word add --bold' 'Added 2 paragraph' word add $doc --bold 'Bold line\nSecond bold line' | Out-Null
Expect 'word replace' 'Replaced 1 time' word replace $doc 2024 2025 | Out-Null
Expect 'word set' 'Changed paragraph 1 ' word set $doc 1 'Namaste Asha,' | Out-Null
Expect 'word delete' 'Deleted paragraph 5 ' word delete $doc 5 | Out-Null
$r = Run word read $doc
$ok = ($r.out -match '(?m)^1: Namaste Asha,\s*$') -and ($r.out -match '(?m)^2: The year is 2025\.\s*$') -and ($r.out -match '(?m)^3: Yours, Termant\s*$') -and ($r.out -match '(?m)^4: Bold line\s*$') -and ($r.out -notmatch 'Second bold') -and ($r.out -match '\(4 paragraphs, 4 with text\)')
Check 'the document holds every change' $ok "exit $($r.code): $($r.out -replace '\s+', ' ')"
$r = Run word replace $doc 'not in there' x
Check 'replacing words that are not there is refused' (($r.code -ne 0) -and ($r.out -match 'is not in the document')) "exit $($r.code): $($r.out)"
$r = Run word set $doc 9 x
Check 'a paragraph number that does not exist is refused' (($r.code -ne 0) -and ($r.out -match '1 to 4')) "exit $($r.code): $($r.out)"
$r = Run word read (Join-Path $Out 'missing.docx')
Check 'a missing document is refused' (($r.code -ne 0) -and ($r.out -match 'no such file')) "exit $($r.code): $($r.out)"
$r = Run word read $log
Check 'a file that is not .docx is refused' (($r.code -ne 0) -and ($r.out -match 'not a \.docx')) "exit $($r.code): $($r.out)"
# a document the way Word writes it: one sentence cut into pieces with different looks, a table, a bold run
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$made = Join-Path $Out 'pieces.docx'
Copy-Item -LiteralPath $doc -Destination $made -Force
$w = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
$xml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="' + $w + '"><w:body>' +
  '<w:p><w:r><w:t>Hel</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t xml:space="preserve">lo wor</w:t></w:r><w:r><w:t>ld, hello all</w:t></w:r></w:p>' +
  '<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Price</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>100</w:t></w:r></w:p></w:tc></w:tr></w:tbl>' +
  '<w:p/><w:sectPr/></w:body></w:document>'
$zip = [IO.Compression.ZipFile]::Open($made, [IO.Compression.ZipArchiveMode]::Update)
$zip.GetEntry('word/document.xml').Delete()
$stream = $zip.CreateEntry('word/document.xml').Open()
$bytes = [Text.Encoding]::UTF8.GetBytes($xml); $stream.Write($bytes, 0, $bytes.Length); $stream.Dispose(); $zip.Dispose()
$r = Run word read $made
Check 'word read joins the pieces of a sentence and reads table cells' (($r.out -match '(?m)^1: Hello world, hello all\s*$') -and ($r.out -match '(?m)^2: Price\s*$') -and ($r.out -match '(?m)^3: 100\s*$') -and ($r.out -match '\(4 paragraphs, 3 with text\)')) "exit $($r.code): $($r.out -replace '\s+', ' ')"
Expect 'word replace finds words that are cut into pieces' 'Replaced 1 time' word replace $made 'Hello world' 'Namaste duniya' | Out-Null
Expect 'word replace inside one piece and in a table cell' 'Replaced 1 time' word replace $made 100 250 | Out-Null
$r = Run word read $made
Check 'both replacements are in the document' (($r.out -match '(?m)^1: Namaste duniya, hello all\s*$') -and ($r.out -match '(?m)^3: 250\s*$')) "exit $($r.code): $($r.out -replace '\s+', ' ')"
$zip = [IO.Compression.ZipFile]::OpenRead($made)
$names = ($zip.Entries | ForEach-Object { $_.FullName }) -join ' '
$reader = New-Object IO.StreamReader($zip.GetEntry('word/document.xml').Open()); $after = $reader.ReadToEnd(); $reader.Dispose(); $zip.Dispose()
Check 'the changed file is still a whole .docx' (($names -match '\[Content_Types\]\.xml') -and ($names -match '_rels/\.rels') -and ($after -match '<w:tbl>') -and ($after -match '<w:b ?/>')) "$names"
Expect 'drag' 'Dragged from 300,300 to 340,300' drag 300 300 340 300 | Out-Null
$r = Run skill list
Check 'learning commands say that the app is not running' (($r.code -ne 0) -and ($r.out -match 'not running')) "exit $($r.code): $($r.out)"

Expect 'mute' 'Muted' mute on -Soft | Out-Null
Expect 'unmute' 'Sound on' mute off -Soft | Out-Null
Expect 'say' 'Said' say hello -Soft | Out-Null
$r = Run brightness
Check 'brightness answers' ($r.out -match 'rightness') "exit $($r.code): $($r.out)"
Expect 'shutdown can be scheduled' 'shuts down in 30' shutdown 30 -Soft | Out-Null
Expect 'and cancelled' 'cancelled' cancel-shutdown -Soft | Out-Null

$summary = "RESULT: $(if ($script:hard -eq 0) { 'PASS' } else { 'FAIL' }) ($($script:hard) failed, $($script:soft) soft-failed)"
Write-Output $summary; Add-Content -Path $log -Value $summary
if ($script:hard -ne 0) { exit 1 }
exit 0

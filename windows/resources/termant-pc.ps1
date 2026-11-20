# The `pc` command: controls this Windows PC. Used by the AI agent, by rules, and by you in the terminal.
#   pc help
# Works in Windows PowerShell 5.1 (the one that comes with Windows).
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch {}

$cmd = if ($args.Count -gt 0) { [string]$args[0] } else { 'help' }
$rest = @(); if ($args.Count -gt 1) { $rest = @($args[1..($args.Count - 1)] | ForEach-Object { [string]$_ }) }
function Words { return ($rest -join ' ') }
function Fail($message) { [Console]::Error.WriteLine($message); exit 1 }
function Need($n, $usage) { if ($rest.Count -lt $n) { Fail "Usage: pc $usage" } }

# ---------------------------------------------------------------- Windows calls
function Native {
  if ('TermantWin' -as [type]) { return }
  Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public class TermantWin {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr h, int how);
  [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, int data, UIntPtr extra);
  [DllImport("user32.dll")] public static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);
  [DllImport("user32.dll")] public static extern bool LockWorkStation();
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, System.Text.StringBuilder text, int max);
  public delegate bool EnumProc(IntPtr h, IntPtr l);
  public struct RECT { public int L, T, R, B; }
  [DllImport("user32.dll")] public static extern bool EnumChildWindows(IntPtr parent, EnumProc proc, IntPtr l);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassName(IntPtr h, System.Text.StringBuilder s, int max);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern IntPtr SendMessage(IntPtr h, uint msg, IntPtr w, System.Text.StringBuilder l);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
  // The classic controls inside a window (text boxes, buttons), read the old way: one per line.
  public static string Controls(IntPtr parent) {
    var lines = new System.Text.StringBuilder();
    EnumChildWindows(parent, delegate(IntPtr h, IntPtr l) {
      if (!IsWindowVisible(h)) return true;
      var kind = new System.Text.StringBuilder(256); GetClassName(h, kind, 256);
      var text = new System.Text.StringBuilder(4000); SendMessage(h, 0x000D, (IntPtr)4000, text);
      RECT r; GetWindowRect(h, out r);
      lines.Append(kind.ToString()).Append('\t').Append(text.ToString().Replace('\t', ' ').Replace('\r', ' ').Replace('\n', ' '))
        .Append('\t').Append((r.L + r.R) / 2).Append(',').Append((r.T + r.B) / 2).Append('\n');
      return true;
    }, IntPtr.Zero);
    return lines.ToString();
  }
}
[Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioEndpointVolume {
  int f(); int g(); int h(); int i();
  int SetMasterVolumeLevelScalar(float level, Guid context);
  int j();
  int GetMasterVolumeLevelScalar(out float level);
  int k(); int l(); int m(); int n();
  int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, Guid context);
  int GetMute(out bool mute);
}
[Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice { int Activate(ref Guid id, int context, int parameters, out IAudioEndpointVolume volume); }
[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator { int f(); int GetDefaultAudioEndpoint(int flow, int role, out IMMDevice device); }
[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorComObject { }
public class TermantAudio {
  static IAudioEndpointVolume Endpoint() {
    var all = new MMDeviceEnumeratorComObject() as IMMDeviceEnumerator;
    IMMDevice device = null;
    Marshal.ThrowExceptionForHR(all.GetDefaultAudioEndpoint(0, 1, out device));
    IAudioEndpointVolume volume = null;
    var id = typeof(IAudioEndpointVolume).GUID;
    Marshal.ThrowExceptionForHR(device.Activate(ref id, 23, 0, out volume));
    return volume;
  }
  public static float Volume {
    get { float v = -1; Marshal.ThrowExceptionForHR(Endpoint().GetMasterVolumeLevelScalar(out v)); return v; }
    set { Marshal.ThrowExceptionForHR(Endpoint().SetMasterVolumeLevelScalar(value, Guid.Empty)); }
  }
  public static bool Mute {
    get { bool m; Marshal.ThrowExceptionForHR(Endpoint().GetMute(out m)); return m; }
    set { Marshal.ThrowExceptionForHR(Endpoint().SetMute(value, Guid.Empty)); }
  }
}
'@
  [void][TermantWin]::SetProcessDPIAware()
}

$Keys = @{
  ctrl = 0x11; control = 0x11; alt = 0x12; shift = 0x10; win = 0x5B; windows = 0x5B
  enter = 0x0D; 'return' = 0x0D; tab = 0x09; esc = 0x1B; escape = 0x1B; space = 0x20; backspace = 0x08
  delete = 0x2E; del = 0x2E; insert = 0x2D; home = 0x24; end = 0x23; pgup = 0x21; pageup = 0x21; pgdn = 0x22; pagedown = 0x22
  left = 0x25; up = 0x26; right = 0x27; down = 0x28; printscreen = 0x2C; menu = 0x5D
  volumeup = 0xAF; volumedown = 0xAE; volumemute = 0xAD; playpause = 0xB3; next = 0xB0; previous = 0xB1
}
function KeyCode($name) {
  $n = $name.ToLower()
  if ($Keys.ContainsKey($n)) { return $Keys[$n] }
  if ($n -match '^f([1-9]|1[0-2])$') { return 0x6F + [int]$Matches[1] }
  if ($n.Length -eq 1) {
    $c = [int][char]$n.ToUpper()
    if (($c -ge 48 -and $c -le 57) -or ($c -ge 65 -and $c -le 90)) { return $c }
  }
  Fail "pc key: I do not know the key '$name'. Examples: enter, ctrl+s, alt+f4, win+d, f5"
}
function Press($combo) {
  Native
  $codes = @($combo -split '\+' | Where-Object { $_ } | ForEach-Object { KeyCode $_ })
  foreach ($c in $codes) { [TermantWin]::keybd_event([byte]$c, 0, 0, [UIntPtr]::Zero) }
  [array]::Reverse($codes)
  foreach ($c in $codes) { [TermantWin]::keybd_event([byte]$c, 0, 2, [UIntPtr]::Zero) }
}

function Windows-List {
  return @(Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and $_.MainWindowTitle } | Sort-Object ProcessName)
}
function Find-Window($text) {
  $all = Windows-List
  $hit = @($all | Where-Object { $_.MainWindowTitle -like "*$text*" -or $_.ProcessName -like "*$text*" })
  if ($hit.Count -eq 0) { Fail "No open window matches '$text'. Open windows: $((($all | ForEach-Object { $_.MainWindowTitle }) -join '; '))" }
  return $hit[0]
}
function Focus($process, $quiet) {
  Native
  $h = $process.MainWindowHandle
  if ([TermantWin]::IsIconic($h)) { [void][TermantWin]::ShowWindowAsync($h, 9); Start-Sleep -Milliseconds 200 }
  if ([TermantWin]::GetForegroundWindow() -eq $h) { return }
  # Windows lets a program bring a window to the front only right after a key press.
  # Alt alone would open the window's menu, so an unused key is pressed while Alt is down.
  [TermantWin]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero)
  [TermantWin]::keybd_event(0xE8, 0, 0, [UIntPtr]::Zero); [TermantWin]::keybd_event(0xE8, 0, 2, [UIntPtr]::Zero)
  [TermantWin]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)
  [void][TermantWin]::SetForegroundWindow($h)
  Start-Sleep -Milliseconds 300
  if (-not $quiet -and [TermantWin]::GetForegroundWindow() -ne $h) { Fail "Windows did not bring '$($process.MainWindowTitle)' to the front. Try again, or click it with: pc click <x> <y>" }
}

# ---------------------------------------------------------------- the app
# Alarms, questions, rules and notes belong to the Termant app; it listens on this computer only.
function App($path, $body) {
  $dir = if ($env:TERMANT_DATA) { $env:TERMANT_DATA } else { Join-Path $env:APPDATA 'Termant' }
  try { $b = Get-Content -Raw -LiteralPath (Join-Path $dir 'bridge.json') | ConvertFrom-Json }
  catch { Fail 'Termant is not running. Start Termant and try again.' }
  $bytes = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress -Depth 5))
  try {
    $r = Invoke-RestMethod -Uri "http://127.0.0.1:$($b.port)$path" -Method Post -Body $bytes `
      -ContentType 'application/json; charset=utf-8' -Headers @{ 'X-Termant' = $b.token } -TimeoutSec 400
  } catch { Fail "Termant did not answer ($($_.Exception.Message)). Is the app running?" }
  if (-not $r.ok) { Fail $r.error }
  if ($r.text) { Write-Output $r.text }
}

switch ($cmd) {
  # ---------------------------------------------------------------- programs and windows
  'open' {
    Need 1 'open <link, file, folder or program> [arguments]'
    $started = $null
    if ($rest.Count -gt 1) { $started = Start-Process -FilePath $rest[0] -ArgumentList $rest[1..($rest.Count - 1)] -PassThru }
    else { $started = Start-Process -FilePath $rest[0] -PassThru }
    # a program started from the background may open behind other windows: bring it to the front
    if ($started) {
      for ($i = 0; $i -lt 12; $i++) {
        Start-Sleep -Milliseconds 250
        try { $started.Refresh(); if ($started.HasExited) { break }; if ($started.MainWindowHandle -ne 0) { Focus $started $true; break } } catch { break }
      }
    }
    Write-Output "Opened $($rest[0])"
  }
  'apps' {
    $all = Get-StartApps | Sort-Object Name
    if ($rest.Count -gt 0) { $f = Words; $all = $all | Where-Object { $_.Name -like "*$f*" } }
    $all | ForEach-Object { $_.Name }
  }
  'app' {
    Need 1 'app <name of an installed app>   (pc apps lists them)'
    $name = Words
    $all = @(Get-StartApps)
    $hit = @($all | Where-Object { $_.Name -eq $name })
    if ($hit.Count -eq 0) { $hit = @($all | Where-Object { $_.Name -like "$name*" }) }
    if ($hit.Count -eq 0) { $hit = @($all | Where-Object { $_.Name -like "*$name*" }) }
    if ($hit.Count -eq 0) {
      try { Start-Process -FilePath $name; Write-Output "Started $name"; break }
      catch { Fail "No installed app matches '$name'. See: pc apps" }
    }
    Start-Process "shell:AppsFolder\$($hit[0].AppID)"
    Write-Output "Opened $($hit[0].Name)"
  }
  'windows' {
    Windows-List | ForEach-Object { "$($_.MainWindowTitle)   [$($_.ProcessName), id $($_.Id)]" }
  }
  'focus' {
    Need 1 'focus <part of a window title or program name>'
    $p = Find-Window (Words); Focus $p
    Write-Output "In front: $($p.MainWindowTitle)"
  }
  'close' {
    Need 1 'close <part of a window title or program name>'
    $p = Find-Window (Words)
    [void]$p.CloseMainWindow()
    Write-Output "Asked to close: $($p.MainWindowTitle)"
  }
  'processes' {
    $all = Get-Process | Sort-Object -Property WorkingSet64 -Descending
    if ($rest.Count -gt 0) { $f = Words; $all = $all | Where-Object { $_.ProcessName -like "*$f*" } }
    $all | Select-Object -First 40 | ForEach-Object { '{0,-28} id {1,-7} {2,6} MB' -f $_.ProcessName, $_.Id, [int]($_.WorkingSet64 / 1MB) }
  }
  'kill' {
    Need 1 'kill <program name or process id>'
    $t = Words
    if ($t -match '^\d+$') { Stop-Process -Id ([int]$t) -Force } else { Stop-Process -Name ($t -replace '\.exe$', '') -Force }
    Write-Output "Stopped $t"
  }

  # ---------------------------------------------------------------- keyboard, mouse, screen
  'type' {
    Need 1 'type <text>'
    Add-Type -AssemblyName System.Windows.Forms
    $text = (Words) -replace '([+^%~(){}\[\]])', '{$1}'
    [System.Windows.Forms.SendKeys]::SendWait($text)
    Write-Output 'Typed.'
  }
  'key' {
    Need 1 'key <key or combination>   e.g. enter, ctrl+s, alt+f4, win+d'
    foreach ($k in $rest) { Press $k; Start-Sleep -Milliseconds 80 }
    Write-Output "Pressed $(Words)"
  }
  'click' {
    Need 2 'click <x> <y> [right|double]'
    Native
    [void][TermantWin]::SetCursorPos([int]$rest[0], [int]$rest[1]); Start-Sleep -Milliseconds 60
    $how = if ($rest.Count -gt 2) { $rest[2] } else { 'left' }
    if ($how -eq 'right') { [TermantWin]::mouse_event(0x08, 0, 0, 0, [UIntPtr]::Zero); [TermantWin]::mouse_event(0x10, 0, 0, 0, [UIntPtr]::Zero) }
    else {
      $times = if ($how -eq 'double') { 2 } else { 1 }
      for ($i = 0; $i -lt $times; $i++) { [TermantWin]::mouse_event(0x02, 0, 0, 0, [UIntPtr]::Zero); [TermantWin]::mouse_event(0x04, 0, 0, 0, [UIntPtr]::Zero); Start-Sleep -Milliseconds 70 }
    }
    Write-Output "Clicked $($rest[0]),$($rest[1])"
  }
  'move' {
    Need 2 'move <x> <y>'
    Native; [void][TermantWin]::SetCursorPos([int]$rest[0], [int]$rest[1]); Write-Output 'Moved.'
  }
  'scroll' {
    Need 1 'scroll <lines; negative scrolls down>'
    Native; [TermantWin]::mouse_event(0x0800, 0, 0, ([int]$rest[0]) * 120, [UIntPtr]::Zero); Write-Output 'Scrolled.'
  }
  'screen' {
    # What the window in front shows: its buttons, fields and texts, each with the point to click.
    Native
    Add-Type -AssemblyName WindowsBase, UIAutomationClient, UIAutomationTypes
    $h = [TermantWin]::GetForegroundWindow()
    $root = [System.Windows.Automation.AutomationElement]::FromHandle($h)
    Write-Output "Window: $($root.Current.Name)"
    # Walk the window's parts one by one (asking for all of them at once misses most in many programs).
    $walker = [System.Windows.Automation.TreeWalker]::RawViewWalker
    $all = New-Object System.Collections.ArrayList
    $queue = New-Object System.Collections.Queue
    $queue.Enqueue($root)
    while ($queue.Count -gt 0 -and $all.Count -lt 700) {
      $node = $queue.Dequeue()
      $child = $null
      try { $child = $walker.GetFirstChild($node) } catch {}
      while ($child -ne $null -and $all.Count -lt 700) {
        [void]$all.Add($child); $queue.Enqueue($child)
        try { $child = $walker.GetNextSibling($child) } catch { $child = $null }
      }
    }
    $lines = New-Object System.Collections.ArrayList
    $hiddenLines = New-Object System.Collections.ArrayList
    foreach ($e in $all) {
      try {
        $c = $e.Current
        $r = $c.BoundingRectangle
        if ($r.IsEmpty) { continue }
        $kind = $c.ControlType.ProgrammaticName -replace '^ControlType\.', ''
        $name = $c.Name
        $value = ''
        $p = $null
        if ($e.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$p)) { $value = $p.Current.Value }
        elseif (($kind -eq 'Document' -or $kind -eq 'Edit') -and $e.TryGetCurrentPattern([System.Windows.Automation.TextPattern]::Pattern, [ref]$p)) { $value = $p.DocumentRange.GetText(600) }
        if (-not $name -and -not $value) { continue }
        if ($kind -in @('Pane', 'Group', 'Custom', 'TitleBar', 'ScrollBar', 'Thumb', 'Image', 'Separator') -and -not $value) { continue }
        $line = "[$kind] $name"
        if ($value) { $v = ($value -replace '\s+', ' ').Trim(); if ($v.Length -gt 600) { $v = $v.Substring(0, 600) + '...' }; $line += " = `"$v`"" }
        $line += " @ $([int]($r.X + $r.Width / 2)),$([int]($r.Y + $r.Height / 2))"
        if ($c.IsOffscreen) { [void]$hiddenLines.Add($line) } else { [void]$lines.Add($line) }
      } catch {}
    }
    if ($lines.Count -eq 0) { $lines = $hiddenLines }
    if ($lines.Count -eq 0) {
      # Nothing that way: read the classic controls of the window (text boxes, buttons) directly.
      foreach ($row in ([TermantWin]::Controls($h) -split "`n")) {
        $f = $row -split "`t"
        if ($f.Count -lt 3 -or -not $f[1].Trim()) { continue }
        $v = $f[1].Trim(); if ($v.Length -gt 600) { $v = $v.Substring(0, 600) + '...' }
        [void]$lines.Add("[$($f[0])] = `"$v`" @ $($f[2])")
      }
    }
    if ($lines.Count -eq 0) {
      Write-Output "(This window gives no readable text: $($all.Count) parts, none with a name.)"
      Write-Output 'Take a picture instead and look at it: pc screenshot <file.png>'
    } else {
      $lines | Select-Object -First 160 | ForEach-Object { Write-Output $_ }
      if ($lines.Count -gt 160) { Write-Output "... ($($lines.Count - 160) more not shown)" }
    }
  }
  'screenshot' {
    Native
    Add-Type -AssemblyName System.Windows.Forms, System.Drawing
    $file = if ($rest.Count -gt 0) { Words } else { Join-Path ([Environment]::GetFolderPath('MyPictures')) ("screenshot-{0:yyyyMMdd-HHmmss}.png" -f (Get-Date)) }
    $file = [IO.Path]::GetFullPath($file)
    $b = [System.Windows.Forms.SystemInformation]::VirtualScreen
    $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($b.X, $b.Y, 0, 0, $bmp.Size)
    $bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
    Write-Output "Saved $file ($($b.Width)x$($b.Height))"
  }

  # ---------------------------------------------------------------- sound, display, power
  'volume' {
    Native
    try {
      if ($rest.Count -gt 0) {
        $v = [Math]::Max(0, [Math]::Min(100, [int]$rest[0]))
        [TermantAudio]::Volume = $v / 100.0
        Write-Output "Volume $v"
      } else {
        Write-Output ("Volume {0}{1}" -f [int][Math]::Round([TermantAudio]::Volume * 100), $(if ([TermantAudio]::Mute) { ' (muted)' } else { '' }))
      }
    } catch { Fail 'This PC has no sound output to set the volume of.' }
  }
  'mute' {
    Native
    $on = -not ($rest.Count -gt 0 -and $rest[0] -eq 'off')
    try { [TermantAudio]::Mute = $on } catch { Fail 'This PC has no sound output.' }
    Write-Output $(if ($on) { 'Muted' } else { 'Sound on' })
  }
  'brightness' {
    try {
      if ($rest.Count -gt 0) {
        $v = [Math]::Max(0, [Math]::Min(100, [int]$rest[0]))
        $m = Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightnessMethods
        Invoke-CimMethod -InputObject $m -MethodName WmiSetBrightness -Arguments @{ Timeout = 1; Brightness = $v } | Out-Null
        Write-Output "Brightness $v"
      } else {
        Write-Output "Brightness $((Get-CimInstance -Namespace root/WMI -ClassName WmiMonitorBrightness).CurrentBrightness)"
      }
    } catch { Fail 'The brightness of this screen cannot be set from Windows (usual on desktop monitors).' }
  }
  'battery' {
    $b = @(Get-CimInstance Win32_Battery)
    if ($b.Count -eq 0) { Write-Output 'No battery: this PC runs on mains power.' }
    else {
      $state = if ($b[0].BatteryStatus -eq 2 -or $b[0].BatteryStatus -ge 6) { 'charging / plugged in' } else { 'on battery' }
      Write-Output "Battery $($b[0].EstimatedChargeRemaining)%, $state"
    }
  }
  'wifi' {
    $ErrorActionPreference = 'Continue' # netsh reports problems on its error output
    if ($rest.Count -eq 0) {
      $out = (netsh wlan show interfaces) 2>&1 | Out-String
      $name = if ($out -match '(?m)^\s*SSID\s*:\s*(.+)$') { $Matches[1].Trim() } else { '' }
      if ($name) { Write-Output "Wi-Fi connected to $name" } else { Write-Output 'Wi-Fi is not connected (or this PC has no Wi-Fi).' }
    } else {
      $state = if ($rest[0] -eq 'off') { 'disabled' } else { 'enabled' }
      $adapter = @(Get-NetAdapter | Where-Object { $_.PhysicalMediaType -match '802\.11' -or $_.Name -match 'Wi-?Fi|WLAN' })
      if ($adapter.Count -eq 0) { Fail 'This PC has no Wi-Fi adapter.' }
      $out = (netsh interface set interface name="$($adapter[0].Name)" admin=$state) 2>&1 | Out-String
      if ($LASTEXITCODE -ne 0) { Fail "Windows refused: turning Wi-Fi on or off needs Termant to be started as administrator. $($out.Trim())" }
      Write-Output "Wi-Fi $state"
    }
  }
  'say' {
    Need 1 'say <text>'
    Add-Type -AssemblyName System.Speech
    $s = New-Object System.Speech.Synthesis.SpeechSynthesizer
    $s.Speak((Words)); $s.Dispose()
    Write-Output 'Said.'
  }
  'lock' { Native; [void][TermantWin]::LockWorkStation(); Write-Output 'Locked.' }
  'sleep' { Write-Output 'Going to sleep.'; & rundll32.exe powrprof.dll,SetSuspendState 0,1,0 }
  'shutdown' {
    $minutes = if ($rest.Count -gt 0) { [int]$rest[0] } else { 1 }
    & shutdown.exe /s /t ($minutes * 60) | Out-Null
    Write-Output "This PC shuts down in $minutes minute(s). Cancel with: pc cancel-shutdown"
  }
  'restart' {
    $minutes = if ($rest.Count -gt 0) { [int]$rest[0] } else { 1 }
    & shutdown.exe /r /t ($minutes * 60) | Out-Null
    Write-Output "This PC restarts in $minutes minute(s). Cancel with: pc cancel-shutdown"
  }
  'cancel-shutdown' { $ErrorActionPreference = 'Continue'; & shutdown.exe /a 2>&1 | Out-Null; Write-Output 'Shutdown cancelled.' }
  'clipboard' {
    $what = if ($rest.Count -gt 0) { $rest[0] } else { 'get' }
    if ($what -eq 'set') {
      $t = if ($rest.Count -gt 1) { $rest[1..($rest.Count - 1)] -join ' ' } else { '' }
      Set-Clipboard -Value $t; Write-Output 'Copied.'
    } else { Get-Clipboard -Raw }
  }
  'info' {
    $os = Get-CimInstance Win32_OperatingSystem
    $cpu = @(Get-CimInstance Win32_Processor)[0]
    Write-Output "$($os.Caption) $($os.Version), computer $($env:COMPUTERNAME), user $($env:USERNAME)"
    Write-Output "Processor: $($cpu.Name.Trim())"
    Write-Output ("Memory: {0:N1} GB free of {1:N1} GB" -f ($os.FreePhysicalMemory / 1MB), ($os.TotalVisibleMemorySize / 1MB))
    Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3' | ForEach-Object {
      Write-Output ("Disk {0} {1:N0} GB free of {2:N0} GB" -f $_.DeviceID, ($_.FreeSpace / 1GB), ($_.Size / 1GB))
    }
  }

  # ---------------------------------------------------------------- through the app
  'notify' {
    Need 1 'notify <title> [text]'
    App '/notify' @{ title = $rest[0]; text = $(if ($rest.Count -gt 1) { $rest[1..($rest.Count - 1)] -join ' ' } else { '' }) }
  }
  'alarm' {
    Need 1 'alarm <HH:MM, 24-hour> [label]'
    App '/alarm' @{ at = $rest[0]; label = $(if ($rest.Count -gt 1) { $rest[1..($rest.Count - 1)] -join ' ' } else { '' }) }
  }
  'timer' {
    Need 1 'timer <30s | 10m | 2h> [label]'
    App '/timer' @{ after = $rest[0]; label = $(if ($rest.Count -gt 1) { $rest[1..($rest.Count - 1)] -join ' ' } else { '' }) }
  }
  'alarms' { App '/alarms' @{ remove = $(if ($rest.Count -gt 1 -and $rest[0] -eq 'remove') { $rest[1] } else { '' }) } }
  'ring' { App '/ring' @{ label = (Words) } }
  'stop' { App '/stop' @{} }
  'ask' {
    Need 1 'ask <question> [choice 1] [choice 2] ...'
    $choices = @(); if ($rest.Count -gt 1) { $choices = @($rest[1..($rest.Count - 1)]) }
    App '/ask' @{ question = $rest[0]; choices = $choices }
  }
  'rule' {
    $what = if ($rest.Count -gt 0) { $rest[0] } else { 'list' }
    if ($what -eq 'add') {
      $tail = @(); if ($rest.Count -gt 1) { $tail = @($rest[1..($rest.Count - 1)]) }
      $cut = -1
      for ($i = 0; $i -lt $tail.Count; $i++) { if ($tail[$i] -eq 'do' -or $tail[$i] -eq '--') { $cut = $i; break } }
      if ($cut -lt 1 -or $cut -ge $tail.Count - 1) { Fail 'Usage: pc rule add <at HH:MM | every 30m | startup | in 10m> do <command>' }
      App '/rule' @{ action = 'add'; trigger = ($tail[0..($cut - 1)] -join ' '); command = ($tail[($cut + 1)..($tail.Count - 1)] -join ' ') }
    } elseif ($what -eq 'remove') { Need 2 'rule remove <id>'; App '/rule' @{ action = 'remove'; id = $rest[1] } }
    else { App '/rule' @{ action = 'list' } }
  }
  'note' {
    $what = if ($rest.Count -gt 0) { $rest[0] } else { 'list' }
    if ($what -eq 'add') { Need 2 'note add <what to remember>'; App '/note' @{ action = 'add'; text = ($rest[1..($rest.Count - 1)] -join ' ') } }
    elseif ($what -eq 'remove') { Need 2 'note remove <number>'; App '/note' @{ action = 'remove'; id = $rest[1] } }
    else { App '/note' @{ action = 'list' } }
  }

  default {
    if ($cmd -ne 'help') { [Console]::Error.WriteLine("pc: unknown command '$cmd'") }
    Write-Output @'
pc: control this PC

Programs and windows
  pc open <link | file | folder | program>     pc app <name>        pc apps [filter]
  pc windows        pc focus <title>           pc close <title>
  pc processes [filter]                        pc kill <name | id>

Keyboard, mouse, screen
  pc type <text>    pc key <enter | ctrl+s | alt+f4 | win+d ...>
  pc click <x> <y> [right|double]              pc move <x> <y>      pc scroll <lines>
  pc screen         what the window in front shows, with the point to click for each thing
  pc screenshot [file.png]

Sound, display, power
  pc volume [0-100] pc mute [on|off]           pc brightness [0-100]
  pc say <text>     pc battery                 pc wifi [on|off]     pc info
  pc lock           pc sleep                   pc shutdown [minutes]  pc restart [minutes]  pc cancel-shutdown
  pc clipboard get  pc clipboard set <text>

With the Termant app (it must be running)
  pc notify <title> [text]
  pc alarm <HH:MM> [label]    pc timer <10m> [label]    pc alarms    pc alarms remove <id>
  pc ring [label]             pc stop
  pc ask <question> [choices ...]              waits for the answer and prints it
  pc rule add <at HH:MM | every 30m | startup | in 10m> do <command>
  pc rule list                pc rule remove <id>
  pc note add <text>          pc note list     pc note remove <number>
'@
    if ($cmd -ne 'help') { exit 1 }
  }
}

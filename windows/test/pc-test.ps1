# Tests the `pc` command on a real Windows desktop (run by the GitHub workflow).
#   powershell -ExecutionPolicy Bypass -File windows\test\pc-test.ps1 <folder for the results>
param([string]$Out = 'pc-test-out')
$ErrorActionPreference = 'Continue'
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$Out = (Resolve-Path $Out).Path
$pc = Join-Path $PSScriptRoot '..\resources\pc.cmd'
$env:TERMANT_DATA = Join-Path $Out 'no-app'   # no Termant app is running in this test
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
Expect 'key' 'Pressed' key delete | Out-Null
Start-Sleep -Milliseconds 600
$r = Run screen
Check 'delete emptied the text' (-not ($r.out -match 'hello termant')) ($r.out -replace '\s+', ' ').Substring(0, [Math]::Min(300, ($r.out -replace '\s+', ' ').Length))

# click the File menu at the point that `pc screen` reported, and look for its entries
if ($screen.out -match '(?m)^\[MenuItem\] File @ (\d+),(\d+)') {
  Expect 'click' 'Clicked' click $Matches[1] $Matches[2] | Out-Null
  Start-Sleep -Seconds 1
  $r = Run screen
  Check 'the click opened the File menu' ($r.out -match 'Save As|Save as|New') ($r.out -replace '\s+', ' ').Substring(0, [Math]::Min(400, ($r.out -replace '\s+', ' ').Length)) -Soft
  Run screenshot (Join-Path $Out 'desktop-menu.png') | Out-Null
  Run key esc | Out-Null
} else { Check 'click' $false 'no File menu in the screen text' -Soft }
Expect 'move' 'Moved' move 200 200 | Out-Null
Expect 'scroll' 'Scrolled' scroll -3 | Out-Null

Expect 'kill' 'Stopped' kill notepad | Out-Null
Start-Sleep -Seconds 1
$r = Run windows
Check 'Notepad is gone' (-not ($r.out -match 'Notepad')) ($r.out -replace '\s+', ' ')

# ---- things a test machine may not have: reported, but they do not fail the test
Expect 'volume set' 'Volume 30' volume 30 -Soft | Out-Null
Expect 'volume get' 'Volume 30' volume -Soft | Out-Null
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

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
function Data-Dir { if ($env:TERMANT_DATA) { return $env:TERMANT_DATA }; return (Join-Path $env:APPDATA 'Termant') }
function App($path, $body) {
  $dir = Data-Dir
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


# ---------------------------------------------------------------- reading a picture of the screen
# Mirage (Cactus Compute, Apache 2.0) is a small vision model that runs on this PC. Its text reader
# finds the writing in a picture, with a box around each piece. Engine and model are fetched once,
# from fixed versions, and checked against their SHA-256 sums.
$MirageModel = @{
  url = 'https://huggingface.co/Cactus-Compute/mirage/resolve/76c1e9e53381aca8fc3843c636216c96ff220122/mirage.cact'
  sha = '4249a0f7515272ff76b9414bec17d7e7daf84b15b66c7fb4f17fce1e66f9f237'
}
$MirageEngine = @{
  url = 'https://huggingface.co/Cactus-Compute/cactus/resolve/f4ab2741203a282c6e7b89983de2b5e5b2e805a8/releases/4.1.4/python/cactus_runtime-4.1.4-py3-none-win_amd64.whl'
  sha = '76f21737d93a5246afe2bfee65a5a338df35217cb6258c7aa9cc747e6ea2bb73'
}
function Fetch($what, $target) {
  $ProgressPreference = 'SilentlyContinue'
  [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
  try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 300 -Uri $what.url -OutFile $target }
  catch { throw "the download failed ($($_.Exception.Message)). It needs the internet once." }
  if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $what.sha) {
    Remove-Item -LiteralPath $target -Force
    throw 'the downloaded file is not the expected one'
  }
}
function Vision {
  if ('TermantVision' -as [type]) { return }
  if ([IntPtr]::Size -ne 8) { throw 'it needs 64-bit Windows' }
  $dir = Join-Path (Data-Dir) 'vision'
  $dll = Join-Path $dir 'libcactus.dll'; $model = Join-Path $dir 'mirage.cact'
  if (-not (Test-Path -LiteralPath $dll) -or -not (Test-Path -LiteralPath $model)) {
    [Console]::Out.WriteLine('(Getting the Mirage reader, 23 MB, once.)')
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    if (-not (Test-Path -LiteralPath $model)) {
      Fetch $MirageModel "$model.part"
      Move-Item -LiteralPath "$model.part" -Destination $model -Force
    }
    if (-not (Test-Path -LiteralPath $dll)) {
      Fetch $MirageEngine "$dll.zip"
      Add-Type -AssemblyName System.IO.Compression.FileSystem
      $zip = [IO.Compression.ZipFile]::OpenRead("$dll.zip")
      try {
        $entry = $zip.Entries | Where-Object { $_.FullName -eq 'cactus/libcactus.dll' }
        if (-not $entry) { throw 'the engine is not in the downloaded file' }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, "$dll.part", $true)
      } finally { $zip.Dispose() }
      Move-Item -LiteralPath "$dll.part" -Destination $dll -Force
      Remove-Item -LiteralPath "$dll.zip" -Force
    }
  }
  Add-Type -AssemblyName System.Drawing
  Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Text;
public class TermantVision {
  [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern IntPtr LoadLibrary(string path);
  [DllImport("libcactus.dll", CallingConvention = CallingConvention.Cdecl)] static extern int cactus_load(byte[] data, ulong size);
  [DllImport("libcactus.dll", CallingConvention = CallingConvention.Cdecl)] static extern int cactus_models();
  [DllImport("libcactus.dll", CallingConvention = CallingConvention.Cdecl)] static extern IntPtr cactus_last_error();
  [DllImport("libcactus.dll", CallingConvention = CallingConvention.Cdecl)] static extern int cactus_describe(byte[] rgb, int width, int height, byte[] options, byte[] language, byte[] keywords, byte[] output, int capacity);
  static byte[] buffer = new byte[4 * 1024 * 1024];
  static string Error() { IntPtr p = cactus_last_error(); return p == IntPtr.Zero ? "unknown error" : Marshal.PtrToStringAnsi(p); }
  public static void Load(string dll, string model) {
    if (LoadLibrary(dll) == IntPtr.Zero) throw new Exception("the engine did not load (Windows error " + Marshal.GetLastWin32Error() + ")");
    byte[] data = System.IO.File.ReadAllBytes(model);
    if (cactus_load(data, (ulong)data.Length) < 0) throw new Exception("the model did not load: " + Error());
    if ((cactus_models() & 4) == 0) throw new Exception("the model file is not a vision model");
  }
  // The picture's pixels inside the rectangle, as packed RGB.
  public static byte[] Rgb(Bitmap picture, Rectangle part) {
    BitmapData data = picture.LockBits(part, ImageLockMode.ReadOnly, PixelFormat.Format24bppRgb);
    byte[] row = new byte[part.Width * 3], rgb = new byte[part.Width * part.Height * 3];
    for (int y = 0, o = 0; y < part.Height; y++) {
      Marshal.Copy(IntPtr.Add(data.Scan0, y * data.Stride), row, 0, row.Length);
      for (int x = 0; x < row.Length; x += 3) { rgb[o++] = row[x + 2]; rgb[o++] = row[x + 1]; rgb[o++] = row[x]; }
    }
    picture.UnlockBits(data);
    return rgb;
  }
  // True for a part that is one flat colour: nothing to read there.
  public static bool Plain(byte[] rgb) {
    int different = 0;
    for (int i = 0; i + 2 < rgb.Length && different < 12; i += 3)
      if (Math.Abs(rgb[i] - rgb[0]) + Math.Abs(rgb[i + 1] - rgb[1]) + Math.Abs(rgb[i + 2] - rgb[2]) > 40) different++;
    return different < 12;
  }
  // The writing in the picture, as the engine's JSON.
  public static string Read(byte[] rgb, int width, int height) { return Describe(rgb, width, height, "{\"sections\":[\"text\"]}"); }
  // What the engine sees in the picture, as its JSON. The options say what it shall give; "{}" is all:
  // the scene, the things with a box around each, the writing, what the things do with each other.
  public static string Describe(byte[] rgb, int width, int height, string options) {
    int n = cactus_describe(rgb, width, height, Encoding.UTF8.GetBytes(options + "\0"), null, null, buffer, buffer.Length);
    if (n < 0) throw new Exception(Error());
    int end = Array.IndexOf(buffer, (byte)0);
    return Encoding.UTF8.GetString(buffer, 0, end < 0 ? n : end);
  }
}
'@
  [TermantVision]::Load($dll, $model)
}
# Where the pieces of a length start when it is cut into overlapping parts of one size.
function Cuts($length, $size) {
  if ($length -le $size) { return ,@(0) }
  $list = @(); $step = [int]($size * 0.9)
  for ($p = 0; $p + $size -lt $length; $p += $step) { $list += $p }
  $list += ($length - $size)
  return ,$list
}
# Prints the writing seen in the window (or on the whole screen), one piece per line with its point.
function Read-Picture($window, $whole) {
  try {
    Vision
    Add-Type -AssemblyName System.Windows.Forms
    $area = [System.Windows.Forms.SystemInformation]::VirtualScreen
    if (-not $whole) {
      $r = New-Object TermantWin+RECT
      if ([TermantWin]::GetWindowRect($window, [ref]$r)) {
        $w = [System.Drawing.Rectangle]::Intersect((New-Object System.Drawing.Rectangle $r.L, $r.T, ($r.R - $r.L), ($r.B - $r.T)), $area)
        if ($w.Width -ge 40 -and $w.Height -ge 40) { $area = $w }
      }
    }
    $started = Get-Date
    $picture = New-Object System.Drawing.Bitmap $area.Width, $area.Height, ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g = [System.Drawing.Graphics]::FromImage($picture)
    $g.CopyFromScreen($area.X, $area.Y, 0, 0, $picture.Size)
    $scale = [Math]::Max(1.0, $g.DpiX / 96.0)
    $g.Dispose()
    # The reader does best on parts of about 640 x 480 at normal text size. Each part takes a few seconds,
    # so a very big window is cut into bigger parts, at most 8.
    $tw = [int](640 * $scale); $th = [int](480 * $scale)
    while ((Cuts $area.Width $tw).Count * (Cuts $area.Height $th).Count -gt 8) { $tw = [int]($tw * 1.2); $th = [int]($th * 1.2) }
    $found = New-Object System.Collections.ArrayList
    $parts = 0
    foreach ($y in (Cuts $area.Height $th)) {
      foreach ($x in (Cuts $area.Width $tw)) {
        $pw = [Math]::Min($tw, $area.Width); $ph = [Math]::Min($th, $area.Height)
        $rgb = [TermantVision]::Rgb($picture, (New-Object System.Drawing.Rectangle $x, $y, $pw, $ph))
        if ([TermantVision]::Plain($rgb)) { continue }
        $parts++
        foreach ($t in @((ConvertFrom-Json ([TermantVision]::Read($rgb, $pw, $ph))).text)) {
          if (-not $t) { continue }
          $text = ([string]$t.string).Trim()
          if (-not $text -or ($text.Length -lt 2 -and $t.score -lt 0.5)) { continue }
          # Writing that touches an inner edge of its part is probably cut off there: the next part has it whole.
          $cut = (($t.bbox[0] -lt 8 -and $x -gt 0) -or ($t.bbox[1] -lt 8 -and $y -gt 0) -or
                  ($t.bbox[2] -gt 992 -and $x + $pw -lt $area.Width) -or ($t.bbox[3] -gt 992 -and $y + $ph -lt $area.Height))
          [void]$found.Add([pscustomobject]@{
            text = $text; score = [double]$t.score; whole = [int](-not $cut)
            x1 = $area.X + $x + $t.bbox[0] * $pw / 1000.0; y1 = $area.Y + $y + $t.bbox[1] * $ph / 1000.0
            x2 = $area.X + $x + $t.bbox[2] * $pw / 1000.0; y2 = $area.Y + $y + $t.bbox[3] * $ph / 1000.0
          })
        }
      }
    }
    $picture.Dispose()
    # The parts overlap, so some writing was read twice: of two boxes on the same spot the surer one stays.
    $kept = New-Object System.Collections.ArrayList
    foreach ($c in ($found | Sort-Object -Property whole, score -Descending)) {
      $twice = $false
      foreach ($k in $kept) {
        $ix = [Math]::Min($c.x2, $k.x2) - [Math]::Max($c.x1, $k.x1); $iy = [Math]::Min($c.y2, $k.y2) - [Math]::Max($c.y1, $k.y1)
        if ($ix -le 0 -or $iy -le 0) { continue }
        $smaller = [Math]::Min(($c.x2 - $c.x1) * ($c.y2 - $c.y1), ($k.x2 - $k.x1) * ($k.y2 - $k.y1))
        if ($ix * $iy -gt 0.5 * $smaller) { $twice = $true; break }
      }
      if (-not $twice) { [void]$kept.Add($c) }
    }
    $seconds = [int]((Get-Date) - $started).TotalSeconds
    if ($kept.Count -eq 0) { Write-Output "(A picture of it was read too, in $seconds s: no writing found.)"; return }
    Write-Output "Read from a picture by Mirage ($parts parts, $seconds s). Letters can be wrong (O/0, l/1, capitals) and only Latin script is read; the points are right:"
    $kept | Sort-Object -Property @{ Expression = { [int](($_.y1 + $_.y2) / 24) } }, x1 | Select-Object -First 220 | ForEach-Object {
      Write-Output "[Text] `"$($_.text)`" @ $([int](($_.x1 + $_.x2) / 2)),$([int](($_.y1 + $_.y2) / 2))"
    }
  } catch { Write-Output "(A picture of it could not be read: $($_.Exception.Message))" }
}

# ---------------------------------------------------------------- what is in a picture
# With the options "{}" Mirage tells all it sees in a picture: the scene, the things with a box around
# each, the writing, and what the things do with each other. The box is about right; Mirage's own words
# for the place ("left foreground") mix up left and right now and then. So the place is worked out here
# from the box, and of Mirage's words only those stay that say something else ("on bus", "background").
# The Android app makes the same lines (android/src/com/termant/app/Look.java); android/mirage/look
# holds answers of Mirage and the lines both must make of them.
$LookPlain = @('left', 'right', 'center', 'centre', 'middle', 'top', 'bottom', 'upper', 'lower', 'edge', 'and',
  'foreground', 'background', 'corner', 'side', 'far', 'of', 'the', 'in', 'at', 'on')
$LookCells = @('top left', 'top', 'top right', 'left', 'middle', 'right', 'bottom left', 'bottom', 'bottom right')
$LookObject = [System.Management.Automation.PSCustomObject]
$LookSplit = [StringSplitOptions]::None
function Look-Prop($o, $name) {
  if ($o -isnot $LookObject) { return $null }
  $p = $o.PSObject.Properties[$name]
  if ($null -eq $p) { return $null }
  $v = $p.Value
  return ,$v
}
function Look-List($o, $name) {
  $v = Look-Prop $o $name
  if ($v -is [array]) { return ,$v }
  return ,@()
}
function Look-IsNumber($v) { return ($v -is [int] -or $v -is [long] -or $v -is [double] -or $v -is [decimal] -or $v -is [single]) }
function Look-Number($v) { if (Look-IsNumber $v) { return [double]$v }; return [double]0 }
# Without line breaks and the like, and not longer than the limit.
function Look-Clean([string]$s, [int]$limit) {
  $b = New-Object System.Text.StringBuilder
  foreach ($c in $s.ToCharArray()) { if ([int]$c -lt 32) { [void]$b.Append(' ') } else { [void]$b.Append($c) } }
  $t = $b.ToString().Trim([char]' ')
  if ($t.Length -gt $limit) { return $t.Substring(0, $limit) }
  return $t
}
# A value as a word: writing as it is, a whole number as its digits, anything else as nothing.
function Look-Word($v, [int]$limit = 80) {
  if ($v -is [string]) { return (Look-Clean $v $limit) }
  if ($v -is [int] -or $v -is [long]) { return ([string]$v) }
  return ''
}
# How a thing looks: the values of its description (colour, clothing, posture...), in Mirage's order.
function Look-Looks($o) {
  $d = Look-Prop $o 'description'
  if ($d -isnot $LookObject) { return '' }
  $parts = New-Object System.Collections.ArrayList
  foreach ($p in $d.PSObject.Properties) { $w = Look-Word $p.Value; if ($w) { [void]$parts.Add($w) } }
  return ($parts -join ', ')
}
# Of Mirage's words for the place, what the box does not say already.
function Look-Position([string]$said) {
  if (-not $said) { return '' }
  $fore = $false; $back = $false
  foreach ($w in ($said.ToLowerInvariant() -split '\s+')) {
    if (-not $w) { continue }
    if ($LookPlain -cnotcontains $w) { return $said }
    if ($w -ceq 'foreground') { $fore = $true }
    if ($w -ceq 'background') { $back = $true }
  }
  if ($fore) { return 'foreground' }
  if ($back) { return 'background' }
  return ''
}
# Left, top, right, bottom on a scale of 0 to 1000; nothing when it is no box.
function Look-Box($o) {
  $b = Look-Prop $o 'bbox'
  if ($b -isnot [array] -or $b.Count -lt 4) { return $null }
  $box = New-Object 'double[]' 4
  for ($i = 0; $i -lt 4; $i++) {
    if (-not (Look-IsNumber $b[$i])) { return $null }
    $box[$i] = [Math]::Min([double]1000, [Math]::Max([double]0, [double]$b[$i]))
  }
  if ($box[2] -le $box[0] -or $box[3] -le $box[1]) { return $null }
  return ,$box
}
function Look-Third([double]$sum) { if ($sum * 3 -lt 2000) { return 0 }; if ($sum * 3 -lt 4000) { return 1 }; return 2 }
function Look-Column($b) { return (Look-Third ($b[0] + $b[2])) }
function Look-Row($b) { return (Look-Third ($b[1] + $b[3])) }
function Look-Cell($b) { return ((Look-Row $b) * 3 + (Look-Column $b)) }
function Look-Place($b) {
  $w = $b[2] - $b[0]; $h = $b[3] - $b[1]
  if ($w * $h -ge 600000) { return 'all over' }
  if ($w -ge 800) { return 'across the ' + @('top', 'middle', 'bottom')[(Look-Row $b)] }
  if ($h -ge 800) { return 'down the ' + @('left', 'middle', 'right')[(Look-Column $b)] }
  return $LookCells[(Look-Cell $b)]
}
function Look-Share($b) {
  $p = [int][Math]::Floor(($b[2] - $b[0]) * ($b[3] - $b[1]) / 10000.0 + 0.5)
  if ($p -lt 1) { return 'under 1%' }
  return ([string]$p + '%')
}
function Look-Percent([double]$score) { return [int][Math]::Floor($score * 100 + 0.5) }
function Look-Sentence($maybe, $names, $who, $does, $whom) {
  if ($null -eq $who) { return }
  $verb = (Look-Word $does).Replace('_', ' ')
  if (-not $names.ContainsKey([string]$who) -or -not $verb) { return }
  $s = $names[[string]$who] + ' ' + $verb
  if ($null -ne $whom) {
    if (-not $names.ContainsKey([string]$whom)) { return }
    $s += ' ' + $names[[string]$whom]
  }
  if (-not $maybe.Contains($s)) { [void]$maybe.Add($s) }
}
# The lines about a picture. $answer: what Mirage answered; $W, $H: the picture's size in pixels;
# $what: how the picture is called in the first line; $tap: added to the heading of the things, to say what
# a point is good for; $ox, $oy: where the picture's top left corner is on the screen (for the points).
function Look-Text($answer, [int]$W, [int]$H, [string]$what, [long]$ms, [string]$device, [string]$tap, [int]$ox, [int]$oy) {
  $out = New-Object System.Collections.ArrayList
  $tenths = [long][Math]::Floor(($ms % 1000) / 100.0)
  [void]$out.Add($what + ' (' + $W + 'x' + $H + '), seen by Mirage in ' + [long][Math]::Floor($ms / 1000.0) + '.' + $tenths + ' s.')
  [void]$out.Add('Mirage is a small model on this ' + $device + ': the places are about right, but a name can be wrong even with a high score (say "it looks like"), it does not know who a person is, and it reads Latin letters only.')

  $reasoning = Look-Prop $answer 'reasoning'
  if ($reasoning -is [string]) {
    $parts = $reasoning.Split([string[]]@(' | '), $LookSplit)
    if ($parts.Count -ge 2 -and $parts[0].StartsWith('lang:', [StringComparison]::Ordinal)) {
      $short = Look-Clean $parts[1] 300
      if ($short) { [void]$out.Add('In short: ' + $short) }
    }
  }

  $scene = Look-Prop $answer 'scene'
  if ($scene -is $LookObject) {
    $s = New-Object System.Collections.ArrayList
    foreach ($p in $scene.PSObject.Properties) {
      $v = Look-Word $p.Value
      if ($v) { [void]$s.Add((Look-Clean $p.Name 40).Replace('_', ' ') + ' = ' + $v) }
    }
    if ($s.Count -gt 0) { [void]$out.Add('Scene: ' + ($s -join '; ')) }
  }

  # ---- the things: each with its box; the same box under two names is one thing "a or b"
  $names = New-Object 'System.Collections.Generic.Dictionary[string,string]'
  $things = New-Object System.Collections.ArrayList
  foreach ($o in (Look-List $answer 'objects')) {
    if ($o -isnot $LookObject) { continue }
    $name = Look-Word (Look-Prop $o 'name')
    $box = Look-Box $o
    if (-not $name -or $null -eq $box) { continue }
    $id = Look-Prop $o 'id'
    if ($null -ne $id -and -not $names.ContainsKey([string]$id)) { $names[[string]$id] = $name }
    $same = $null
    foreach ($t in $things) {
      if ($t.box[0] -eq $box[0] -and $t.box[1] -eq $box[1] -and $t.box[2] -eq $box[2] -and $t.box[3] -eq $box[3]) { $same = $t; break }
    }
    if ($null -ne $same) {
      if ($same.name.Split([string[]]@(' or '), $LookSplit) -cnotcontains $name) { $same.name = $same.name + ' or ' + $name }
      continue
    }
    [void]$things.Add(@{
      name = $name; score = (Look-Number (Look-Prop $o 'score')); box = $box
      position = (Look-Position (Look-Word (Look-Prop $o 'position'))); looks = (Look-Looks $o); at = $things.Count
    })
  }
  $groups = New-Object 'System.Collections.Generic.Dictionary[string,object]'
  $order = New-Object System.Collections.ArrayList
  foreach ($t in $things) {
    if (-not $groups.ContainsKey($t.name)) { $groups[$t.name] = New-Object System.Collections.ArrayList; [void]$order.Add($t.name) }
    [void]$groups[$t.name].Add($t)
  }
  $byScore = @{ Expression = { $_.score }; Descending = $true }, @{ Expression = { $_.at }; Descending = $false }
  $lines = New-Object System.Collections.ArrayList
  foreach ($name in $order) {
    $g = @($groups[$name].ToArray() | Sort-Object -Property $byScore)
    if ($g.Count -lt 4) {
      foreach ($t in $g) {
        $b = $t.box
        $s = '- ' + $name + ', ' + (Look-Percent $t.score) + '%: ' + (Look-Place $b) + ', ' + (Look-Share $b) + ' of the picture'
        if ($t.position) { $s += '; ' + $t.position }
        if ($t.looks) { $s += '; ' + $t.looks }
        $s += ' @' + ($ox + [long][Math]::Floor(($b[0] + $b[2]) * $W / 2000.0)) + ',' + ($oy + [long][Math]::Floor(($b[1] + $b[3]) * $H / 2000.0))
        [void]$lines.Add(@{ score = $t.score; at = $t.at; text = $s })
      }
    } else {
      # many of one kind (the windows of a house): one line, with how many are where
      $count = New-Object 'int[]' 9
      foreach ($t in $g) { $c = Look-Cell $t.box; $count[$c] = $count[$c] + 1 }
      $cells = @(0..8 | Where-Object { $count[$_] -gt 0 } | Sort-Object -Property @{ Expression = { $count[$_] }; Descending = $true }, @{ Expression = { $_ }; Descending = $false })
      $where = New-Object System.Collections.ArrayList
      foreach ($c in $cells) { [void]$where.Add([string]$count[$c] + ' ' + $LookCells[$c]) }
      $s = '- ' + $name + ' x' + $g.Count + ', ' + (Look-Percent $g[$g.Count - 1].score) + '-' + (Look-Percent $g[0].score) + '%: ' + ($where -join ', ')
      [void]$lines.Add(@{ score = $g[0].score; at = $g[0].at; text = $s })
    }
  }
  $sorted = @($lines.ToArray() | Sort-Object -Property $byScore)
  if ($sorted.Count -gt 0) {
    [void]$out.Add('Things (' + $things.Count + '), highest score first. Each line: name, score, where its middle is, how much of the picture it covers, how it looks, and its middle point' + $tap + ':')
    for ($i = 0; $i -lt $sorted.Count -and $i -lt 30; $i++) { [void]$out.Add($sorted[$i].text) }
    if ($sorted.Count -gt 30) { [void]$out.Add('(' + ($sorted.Count - 30) + ' more left out)') }
  }

  # ---- what Mirage names without a box
  $also = New-Object System.Collections.ArrayList
  foreach ($o in (Look-List $answer 'regions')) {
    if ($o -isnot $LookObject) { continue }
    $name = Look-Word (Look-Prop $o 'name')
    if (-not $name) { continue }
    $id = Look-Prop $o 'id'
    if ($null -ne $id -and -not $names.ContainsKey([string]$id)) { $names[[string]$id] = $name }
    if ($groups.ContainsKey($name)) { continue }
    $extra = New-Object System.Collections.ArrayList
    foreach ($x in @((Look-Word (Look-Prop $o 'count')), (Look-Word (Look-Prop $o 'position')), (Look-Looks $o))) { if ($x) { [void]$extra.Add($x) } }
    if ($extra.Count -gt 0) { [void]$also.Add($name + ' (' + ($extra -join '; ') + ')') } else { [void]$also.Add($name) }
  }
  if ($also.Count -gt 0) { [void]$out.Add('Also there, place not marked: ' + (@($also.ToArray() | Select-Object -First 12) -join ', ')) }

  # ---- the writing
  $writing = New-Object System.Collections.ArrayList
  foreach ($t in (Look-List $answer 'text')) {
    if ($t -isnot $LookObject) { continue }
    $s = Look-Word (Look-Prop $t 'string') 120
    $b = Look-Box $t
    if (-not $s -or $null -eq $b) { continue }
    if ($s.Length -lt 2 -and (Look-Number (Look-Prop $t 'score')) -lt 0.5) { continue }   # mostly an icon taken for a letter
    [void]$writing.Add('- "' + $s + '": ' + $LookCells[(Look-Cell $b)] + ' @' + ($ox + [long][Math]::Floor(($b[0] + $b[2]) * $W / 2000.0)) + ',' + ($oy + [long][Math]::Floor(($b[1] + $b[3]) * $H / 2000.0)))
  }
  if ($writing.Count -gt 0) {
    [void]$out.Add('Writing (letters can be wrong):')
    for ($i = 0; $i -lt $writing.Count -and $i -lt 40; $i++) { [void]$out.Add($writing[$i]) }
  }

  # ---- who does what with what
  $maybe = New-Object 'System.Collections.Generic.List[string]'
  foreach ($e in (Look-List $answer 'events')) {
    if ($e -is $LookObject) { Look-Sentence $maybe $names (Look-Prop $e 'actor') (Look-Prop $e 'action') (Look-Prop $e 'target') }
  }
  foreach ($e in (Look-List $answer 'relations')) {
    if ($e -is $LookObject -and $null -ne (Look-Prop $e 'object')) { Look-Sentence $maybe $names (Look-Prop $e 'subject') (Look-Prop $e 'predicate') (Look-Prop $e 'object') }
  }
  if ($maybe.Count -gt 0) { [void]$out.Add('Maybe (this part is often wrong): ' + (@($maybe.ToArray() | Select-Object -First 8) -join '; ')) }

  if ($sorted.Count -eq 0 -and $also.Count -eq 0 -and $writing.Count -eq 0) { [void]$out.Add('Nothing was recognised in it.') }
  return ,$out.ToArray()
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
    #   pc screen            asks the program; reads a picture of the window too when that gives nothing
    #   pc screen ocr        always reads the picture too      pc screen ocr full   ...of the whole screen
    Native
    Add-Type -AssemblyName WindowsBase, UIAutomationClient, UIAutomationTypes
    $picture = ($rest.Count -gt 0 -and $rest[0] -eq 'ocr')
    $whole = ($rest.Count -gt 1 -and $rest[1] -eq 'full')
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
    $named = $lines.Count
    if ($lines.Count -eq 0) {
      # Nothing that way: read the classic controls of the window (text boxes, buttons) directly.
      foreach ($row in ([TermantWin]::Controls($h) -split "`n")) {
        $f = $row -split "`t"
        if ($f.Count -lt 3 -or -not $f[1].Trim()) { continue }
        $v = $f[1].Trim(); if ($v.Length -gt 600) { $v = $v.Substring(0, 600) + '...' }
        [void]$lines.Add("[$($f[0])] = `"$v`" @ $($f[2])")
      }
    }
    $lines | Select-Object -First 160 | ForEach-Object { Write-Output $_ }
    if ($lines.Count -gt 160) { Write-Output "... ($($lines.Count - 160) more not shown)" }
    # A program that names fewer than three of its parts is read from a picture of its window as well.
    if ($picture -or $named -lt 3) { Read-Picture $h $whole }
    elseif ($lines.Count -eq 0) { Write-Output '(This window gives no readable text. Try: pc screen ocr)' }
  }
  'look' {
    # What is in a picture and where: the scene, each thing with its place, the writing.
    #   pc look FILE     a picture file        pc look     the window in front        pc look full     the whole screen
    $file = $null
    if ($rest.Count -gt 0 -and -not ($rest.Count -eq 1 -and $rest[0] -eq 'full')) {
      $file = Words
      if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { Fail "pc look: there is no such file: $file" }
      $file = (Resolve-Path -LiteralPath $file).Path
    }
    try {
      Vision
      $ox = 0; $oy = 0
      if ($file) {
        try { $source = [System.Drawing.Image]::FromFile($file) }
        catch { throw "$([IO.Path]::GetFileName($file)) is not a picture Windows can open (JPEG, PNG, BMP, GIF, TIFF)" }
        # a photo is stored as the camera was held; turn it the way it is looked at
        if ($source.PropertyIdList -contains 274) {
          switch ([int]$source.GetPropertyItem(274).Value[0]) {
            3 { $source.RotateFlip([System.Drawing.RotateFlipType]::Rotate180FlipNone) }
            6 { $source.RotateFlip([System.Drawing.RotateFlipType]::Rotate90FlipNone) }
            8 { $source.RotateFlip([System.Drawing.RotateFlipType]::Rotate270FlipNone) }
          }
        }
        $what = 'Picture ' + [IO.Path]::GetFileName($file); $tap = " (in the picture's own pixels)"
      } else {
        Native
        Add-Type -AssemblyName System.Windows.Forms
        $area = [System.Windows.Forms.SystemInformation]::VirtualScreen
        $what = 'The whole screen as a picture'
        if ($rest.Count -eq 0) {
          $r = New-Object TermantWin+RECT
          if ([TermantWin]::GetWindowRect([TermantWin]::GetForegroundWindow(), [ref]$r)) {
            $front = [System.Drawing.Rectangle]::Intersect((New-Object System.Drawing.Rectangle $r.L, $r.T, ($r.R - $r.L), ($r.B - $r.T)), $area)
            if ($front.Width -ge 40 -and $front.Height -ge 40) { $area = $front; $what = 'The window in front as a picture' }
          }
        }
        $source = New-Object System.Drawing.Bitmap $area.Width, $area.Height, ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
        $g = [System.Drawing.Graphics]::FromImage($source)
        $g.CopyFromScreen($area.X, $area.Y, 0, 0, $source.Size)
        $g.Dispose()
        $ox = $area.X; $oy = $area.Y; $tap = ' (pc click X Y clicks there)'
      }
      $pw = $source.Width; $ph = $source.Height
      if ($pw -lt 8 -or $ph -lt 8) { throw 'the picture is too small' }
      # the model looks at a small picture anyway: a big one is made 1024 points long at most
      $scale = [Math]::Min(1.0, 1024.0 / [Math]::Max($pw, $ph))
      $sw = [Math]::Max(8, [int][Math]::Floor($pw * $scale)); $sh = [Math]::Max(8, [int][Math]::Floor($ph * $scale))
      $small = New-Object System.Drawing.Bitmap $sw, $sh, ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
      $g = [System.Drawing.Graphics]::FromImage($small)
      $g.Clear([System.Drawing.Color]::White)
      $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBilinear
      $g.DrawImage($source, 0, 0, $sw, $sh)
      $g.Dispose(); $source.Dispose()
      $started = Get-Date
      $rgb = [TermantVision]::Rgb($small, (New-Object System.Drawing.Rectangle 0, 0, $sw, $sh))
      $small.Dispose()
      $answer = ConvertFrom-Json ([TermantVision]::Describe($rgb, $sw, $sh, '{}'))
      $ms = [long]((Get-Date) - $started).TotalMilliseconds
      $lines = Look-Text $answer $pw $ph $what $ms 'PC' $tap $ox $oy
    } catch { Fail "pc look: the picture could not be looked at: $($_.Exception.Message)" }
    $lines | ForEach-Object { Write-Output $_ }
  }
  'look-answer' {
    # For the tests only: the lines that `pc look` makes of an answer of Mirage kept in a file.
    Need 5 'look-answer <answer.json> <width> <height> <name> <device>'
    $answer = ConvertFrom-Json ([IO.File]::ReadAllText((Resolve-Path -LiteralPath $rest[0]).Path, [Text.Encoding]::UTF8))
    $lines = Look-Text $answer ([int]$rest[1]) ([int]$rest[2]) $rest[3] 430 $rest[4] '' 0 0
    $lines | ForEach-Object { Write-Output $_ }
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
  pc screen ocr     the same, and always reads a picture of the window too (pc screen ocr full: the whole screen)
  pc look <file>    what is in a photo and where: the scene, each thing with its place, the writing
  pc look           the same for the window in front as a picture (pc look full: the whole screen)
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

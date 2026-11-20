# A one-off probe: how does Mirage's text reader do on Windows screens? (removed again after the answer is known)
param([string]$Out = 'probe-out')
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$Out = (Resolve-Path $Out).Path
$log = Join-Path $Out 'probe.log'
function Say($t) { Write-Output $t; Add-Content -Path $log -Value $t -Encoding UTF8 }
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'

$wheel = Join-Path $Out 'runtime.whl'; $weights = Join-Path $Out 'mirage.cact'; $dll = Join-Path $Out 'libcactus.dll'
$t = Measure-Command { Invoke-WebRequest -UseBasicParsing -Uri 'https://huggingface.co/Cactus-Compute/cactus/resolve/f4ab2741203a282c6e7b89983de2b5e5b2e805a8/releases/4.1.4/python/cactus_runtime-4.1.4-py3-none-win_amd64.whl' -OutFile $wheel }
Say "wheel: $((Get-Item $wheel).Length) bytes in $([int]$t.TotalMilliseconds) ms, sha256 $((Get-FileHash $wheel -Algorithm SHA256).Hash.ToLower())"
$t = Measure-Command { Invoke-WebRequest -UseBasicParsing -Uri 'https://huggingface.co/Cactus-Compute/mirage/resolve/76c1e9e53381aca8fc3843c636216c96ff220122/mirage.cact' -OutFile $weights }
Say "weights: $((Get-Item $weights).Length) bytes in $([int]$t.TotalMilliseconds) ms, sha256 $((Get-FileHash $weights -Algorithm SHA256).Hash.ToLower())"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($wheel)
foreach ($e in $zip.Entries) { Say "  in wheel: $($e.FullName) $($e.Length)" }
$entry = $zip.Entries | Where-Object { $_.FullName -eq 'cactus/libcactus.dll' }
[IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dll, $true)
$zip.Dispose()
Say "dll: $((Get-Item $dll).Length) bytes"

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
  [DllImport("libcactus.dll", CallingConvention = CallingConvention.Cdecl)] static extern void cactus_set_threads(int n);
  public static void Threads(int n) { cactus_set_threads(n); }
  static byte[] buffer = new byte[4 * 1024 * 1024];
  static string Error() { IntPtr p = cactus_last_error(); return p == IntPtr.Zero ? "unknown error" : Marshal.PtrToStringAnsi(p); }
  public static int Load(string dll, string weights) {
    if (LoadLibrary(dll) == IntPtr.Zero) throw new Exception("the engine did not load: Windows error " + Marshal.GetLastWin32Error());
    byte[] data = System.IO.File.ReadAllBytes(weights);
    if (cactus_load(data, (ulong)data.Length) < 0) throw new Exception("the model did not load: " + Error());
    return cactus_models();
  }
  // The picture's pixels inside the rectangle as packed RGB.
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
  public static string Describe(byte[] rgb, int width, int height, string options) {
    int n = cactus_describe(rgb, width, height, Encoding.UTF8.GetBytes(options + "\0"), null, null, buffer, buffer.Length);
    if (n < 0) throw new Exception(Error());
    int end = Array.IndexOf(buffer, (byte)0);
    return Encoding.UTF8.GetString(buffer, 0, end < 0 ? n : end);
  }
}
'@
$t = Measure-Command { $models = [TermantVision]::Load($dll, $weights) }
Say "loaded in $([int]$t.TotalMilliseconds) ms, models bitmask $models"

function Try-Describe($name, $bmp, $rect, $options) {
  try {
    $rgb = [TermantVision]::Rgb($bmp, $rect)
    $json = ''
    $t = Measure-Command { $script:json = [TermantVision]::Describe($rgb, $rect.Width, $rect.Height, $options) }
    $short = if ($script:json.Length -gt 6000) { $script:json.Substring(0, 6000) + ' ...' } else { $script:json }
    Say "=== $name  $($rect.Width)x$($rect.Height)  options $options  $([int]$t.TotalMilliseconds) ms  $($script:json.Length) chars"
    Say $short
    Set-Content -Path (Join-Path $Out "$name.json") -Value $script:json -Encoding UTF8
  } catch { Say "=== $name FAILED: $($_.Exception.Message)" }
}
function Whole($bmp) { return New-Object System.Drawing.Rectangle 0, 0, $bmp.Width, $bmp.Height }

# ---- 2. the real desktop: Notepad, maximised, with text and its File menu open
$pc = Join-Path $PSScriptRoot '..\..\windows\resources\pc.cmd'
$ErrorActionPreference = 'Continue'
& $pc open notepad | Out-Null; Start-Sleep -Seconds 3
& $pc focus Notepad | Out-Null; Start-Sleep -Seconds 1
& $pc type 'Hello from Termant. Buy milk, call Asha at 5 pm.' | Out-Null
& $pc key win+up | Out-Null; Start-Sleep -Seconds 1
& $pc click 20 32 | Out-Null; Start-Sleep -Seconds 1
& $pc screenshot (Join-Path $Out 'desktop.png') | Out-Null
& $pc key esc | Out-Null
& $pc kill notepad | Out-Null
$ErrorActionPreference = 'Stop'
$shot = New-Object System.Drawing.Bitmap (Join-Path $Out 'desktop.png')
$desk = $shot.Clone((Whole $shot), [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
Say "desktop: $($desk.Width)x$($desk.Height)"
Say "processors: $([Environment]::ProcessorCount)"
$blank = New-Object System.Drawing.Bitmap 512, 384, ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
$gb = [System.Drawing.Graphics]::FromImage($blank); $gb.Clear([System.Drawing.Color]::White); $gb.Dispose()
Try-Describe 'blank' $blank (Whole $blank) '{"sections":["text"]}'
Try-Describe 'blank-again' $blank (Whole $blank) '{"sections":["text"]}'
foreach ($size in @(@(512, 384), @(640, 480), @(768, 576), @(1024, 384))) {
  Try-Describe "tile-$($size[0])x$($size[1])" $desk (New-Object System.Drawing.Rectangle 0, 0, $size[0], $size[1]) '{"sections":["text"]}'
}
Try-Describe 'tile-512-conf02' $desk (New-Object System.Drawing.Rectangle 0, 0, 512, 384) '{"sections":["text"],"text_conf":0.2}'
Try-Describe 'tile-512-right' $desk (New-Object System.Drawing.Rectangle 512, 0, 512, 384) '{"sections":["text"]}'
Try-Describe 'tile-512-bottomright' $desk (New-Object System.Drawing.Rectangle 512, 384, 512, 384) '{"sections":["text"]}'
foreach ($n in 1, 2, 4, 8, 0) {
  [TermantVision]::Threads($n)
  Try-Describe "threads-$n" $desk (New-Object System.Drawing.Rectangle 0, 0, 512, 384) '{"sections":["text"]}'
}
Say 'done'

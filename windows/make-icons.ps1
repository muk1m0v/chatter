param([string]$Source = (Join-Path $PSScriptRoot 'assets\salom-logo-512.png'))
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$assetDir = Join-Path $PSScriptRoot 'assets'
New-Item -ItemType Directory -Force -Path $assetDir | Out-Null
$sourceImage = [System.Drawing.Image]::FromFile($Source)
try {
    if ($sourceImage.Width -ne $sourceImage.Height) { throw 'SALOM logo must be square.' }
    $frames = @()
    foreach ($size in @(16,24,32,48,64,128,256)) {
        $bitmap = [System.Drawing.Bitmap]::new($size, $size)
        try {
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            try {
                $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
                $graphics.DrawImage($sourceImage, 0, 0, $size, $size)
            } finally { $graphics.Dispose() }
            $stream = [System.IO.MemoryStream]::new()
            try { $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png); $frames += ,@($size, $stream.ToArray()) }
            finally { $stream.Dispose() }
        } finally { $bitmap.Dispose() }
    }
    $file = [System.IO.File]::Create((Join-Path $assetDir 'salom.ico'))
    try {
        $writer = [System.IO.BinaryWriter]::new($file)
        $writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]$frames.Count)
        $offset = 6 + 16 * $frames.Count
        foreach ($frame in $frames) {
            $size = [int]$frame[0]; $bytes = [byte[]]$frame[1]
            $writer.Write([byte]($size % 256)); $writer.Write([byte]($size % 256))
            $writer.Write([byte]0); $writer.Write([byte]0)
            $writer.Write([uint16]1); $writer.Write([uint16]32)
            $writer.Write([uint32]$bytes.Length); $writer.Write([uint32]$offset)
            $offset += $bytes.Length
        }
        foreach ($frame in $frames) { $writer.Write([byte[]]$frame[1]) }
        $writer.Flush()
    } finally { $file.Dispose() }
} finally { $sourceImage.Dispose() }

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$assets = Join-Path $root 'FireDrop\Assets'
$web = Join-Path $root 'FireDrop\Web'
New-Item -ItemType Directory -Force -Path $assets, $web | Out-Null

function New-RoundedPath([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $p.AddArc($x, $y, $d, $d, 180, 90)
    $p.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $p.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $p.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    return $p
}

function New-FireDropBitmap([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)

    $s = $size / 1024.0
    $inset = 20 * $s
    $radius = 226 * $s
    $rect = [System.Drawing.RectangleF]::new($inset, $inset, ($size - 2 * $inset), ($size - 2 * $inset))
    $path = New-RoundedPath $rect.X $rect.Y $rect.Width $rect.Height $radius

    $bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        ([System.Drawing.PointF]::new(0, 0)),
        ([System.Drawing.PointF]::new($size, $size)),
        [System.Drawing.Color]::FromArgb(255, 34, 211, 238),
        [System.Drawing.Color]::FromArgb(255, 79, 70, 229))
    $blend = New-Object System.Drawing.Drawing2D.ColorBlend
    $blend.Colors = @(
        [System.Drawing.Color]::FromArgb(255, 34, 211, 238),
        [System.Drawing.Color]::FromArgb(255, 14, 165, 233),
        [System.Drawing.Color]::FromArgb(255, 99, 102, 241),
        [System.Drawing.Color]::FromArgb(255, 79, 70, 229)
    )
    $blend.Positions = @(0.0, 0.36, 0.72, 1.0)
    $bg.InterpolationColors = $blend
    $g.FillPath($bg, $path)

    # Soft highlight in the top-left corner
    $glowPath = New-RoundedPath (70 * $s) (58 * $s) (520 * $s) (310 * $s) (150 * $s)
    $glow = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        ([System.Drawing.PointF]::new(0, (58 * $s))),
        ([System.Drawing.PointF]::new(0, (390 * $s))),
        [System.Drawing.Color]::FromArgb(62, 255, 255, 255),
        [System.Drawing.Color]::FromArgb(0, 255, 255, 255))
    $g.FillPath($glow, $glowPath)

    # Subtle inner stroke
    $stroke = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(55, 255, 255, 255), (5 * $s))
    $g.DrawPath($stroke, $path)

    # Soft shadow under the arrow
    $shadowBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(45, 24, 20, 80))
    $shadowPath = New-Object System.Drawing.Drawing2D.GraphicsPath
    $shadowPath.AddEllipse(300 * $s, 730 * $s, 424 * $s, 74 * $s)
    $g.FillPath($shadowBrush, $shadowPath)

    $white = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)

    # Arrow shaft
    $shaft = New-RoundedPath (466 * $s) (228 * $s) (92 * $s) (356 * $s) (46 * $s)
    $g.FillPath($white, $shaft)

    # Arrow head with rounded joints
    $head = New-Object System.Drawing.Drawing2D.GraphicsPath
    $head.AddPolygon(@(
        ([System.Drawing.PointF]::new(334 * $s, 474 * $s)),
        ([System.Drawing.PointF]::new(512 * $s, 654 * $s)),
        ([System.Drawing.PointF]::new(690 * $s, 474 * $s)),
        ([System.Drawing.PointF]::new(690 * $s, 558 * $s)),
        ([System.Drawing.PointF]::new(512 * $s, 738 * $s)),
        ([System.Drawing.PointF]::new(334 * $s, 558 * $s))
    ))
    $g.FillPath($white, $head)

    # Tray / speed line
    $tray = New-RoundedPath (302 * $s) (794 * $s) (420 * $s) (74 * $s) (37 * $s)
    $g.FillPath($white, $tray)

    # Small accent dots (speed)
    $dot = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(190, 255, 255, 255))
    $g.FillEllipse($dot, 222 * $s, 794 * $s, 44 * $s, 44 * $s)
    $g.FillEllipse($dot, 758 * $s, 794 * $s, 44 * $s, 44 * $s)

    $g.Dispose()
    $bg.Dispose(); $glow.Dispose(); $stroke.Dispose(); $shadowBrush.Dispose(); $white.Dispose(); $dot.Dispose()
    $path.Dispose(); $glowPath.Dispose(); $shadowPath.Dispose(); $shaft.Dispose(); $head.Dispose(); $tray.Dispose()
    return $bmp
}

$master = New-FireDropBitmap 1024
$master.Save((Join-Path $assets 'icon-1024.png'), [System.Drawing.Imaging.ImageFormat]::Png)

foreach ($s in @(512, 192, 180, 64, 48, 32, 24, 16)) {
    $bmp = New-Object System.Drawing.Bitmap($s, $s, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.DrawImage($master, 0, 0, $s, $s)
    if ($s -eq 512) { $bmp.Save((Join-Path $web 'icon-512.png'), [System.Drawing.Imaging.ImageFormat]::Png) }
    if ($s -eq 192) { $bmp.Save((Join-Path $web 'icon-192.png'), [System.Drawing.Imaging.ImageFormat]::Png) }
    if ($s -eq 180) { $bmp.Save((Join-Path $web 'apple-touch-icon.png'), [System.Drawing.Imaging.ImageFormat]::Png) }
    if ($s -eq 32) { $bmp.Save((Join-Path $web 'favicon-32.png'), [System.Drawing.Imaging.ImageFormat]::Png) }
    $g.Dispose()
    $bmp.Dispose()
}
$master.Dispose()

# Build a multi-resolution ICO with PNG-compressed frames.
$icoSizes = @(16, 24, 32, 48, 64, 128, 256)
$pngs = @()
foreach ($s in $icoSizes) {
    $src = New-FireDropBitmap $s
    $ms = New-Object System.IO.MemoryStream
    $src.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $pngs += ,@($s, $ms.ToArray())
    $ms.Dispose(); $src.Dispose()
}

$icoPath = Join-Path $assets 'FireDrop.ico'
$fs = [System.IO.File]::Create($icoPath)
$bw = New-Object System.IO.BinaryWriter($fs)
$bw.Write([UInt16]0)      # reserved
$bw.Write([UInt16]1)      # type: icon
$bw.Write([UInt16]$pngs.Count)
$offset = 6 + (16 * $pngs.Count)
foreach ($entry in $pngs) {
    $s = $entry[0]; $data = $entry[1]
    $bw.Write([Byte]$(if ($s -ge 256) { 0 } else { $s }))
    $bw.Write([Byte]$(if ($s -ge 256) { 0 } else { $s }))
    $bw.Write([Byte]0); $bw.Write([Byte]0)
    $bw.Write([UInt16]1); $bw.Write([UInt16]32)
    $bw.Write([UInt32]$data.Length)
    $bw.Write([UInt32]$offset)
    $offset += $data.Length
}
foreach ($entry in $pngs) { $bw.Write($entry[1]) }
$bw.Flush(); $bw.Dispose(); $fs.Dispose()

# A small brand mark for the readme / preview
$preview = New-Object System.Drawing.Bitmap(1200, 630)
$pg = [System.Drawing.Graphics]::FromImage($preview)
$pg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
$bgBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    ([System.Drawing.PointF]::new(0, 0)), ([System.Drawing.PointF]::new(1200, 630)),
    [System.Drawing.Color]::FromArgb(255, 7, 11, 20), [System.Drawing.Color]::FromArgb(255, 14, 23, 43))
$pg.FillRectangle($bgBrush, 0, 0, 1200, 630)
$logo = New-FireDropBitmap 300
$pg.DrawImage($logo, 120, 165, 300, 300)
$fontBig = New-Object System.Drawing.Font('Segoe UI', 72, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$fontSmall = New-Object System.Drawing.Font('Segoe UI', 30, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$whiteBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 242, 246, 255))
$mutedBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(255, 141, 154, 183))
$pg.DrawString('FireDrop', $fontBig, $whiteBrush, 480, 225)
$pg.DrawString('Files from phone to PC over Wi-Fi', $fontSmall, $mutedBrush, 485, 325)
$pg.DrawString('Fast. Local. No cloud.', $fontSmall, $mutedBrush, 485, 380)
$preview.Save((Join-Path $root 'preview.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$pg.Dispose(); $preview.Dispose(); $logo.Dispose(); $bgBrush.Dispose(); $whiteBrush.Dispose(); $mutedBrush.Dispose(); $fontBig.Dispose(); $fontSmall.Dispose()

Write-Host "Icons generated: $icoPath"


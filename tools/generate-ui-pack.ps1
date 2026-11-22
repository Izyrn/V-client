# Draws the "VYRN UI" resource pack (ice hotbar, hearts and boss bars) from scratch - no Mojang art is copied - and
# writes it to src/main/resources/vclient-pack/VYRN UI.zip, where the launcher installs it from. Also brings the
# Ultra Smooth PvP pack's pack.mcmeta up to date for 1.21.9+ (min_format/max_format), which 1.21.11 needs to load it.
#
#   powershell -File tools/generate-ui-pack.ps1
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repoRoot = Split-Path -Parent $PSScriptRoot
$packDir = Join-Path $repoRoot "src\main\resources\vclient-pack"

function Color([string]$hex, [int]$alpha = 255) {
    $c = [System.Drawing.ColorTranslator]::FromHtml($hex)
    return [System.Drawing.Color]::FromArgb($alpha, $c.R, $c.G, $c.B)
}
function Mix($a, $b, [double]$t) {
    return [System.Drawing.Color]::FromArgb([int]($a.A + ($b.A - $a.A) * $t), [int]($a.R + ($b.R - $a.R) * $t),
        [int]($a.G + ($b.G - $a.G) * $t), [int]($a.B + ($b.B - $a.B) * $t))
}
function NewImage([int]$w, [int]$h) { return New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb) }
function PngBytes($bitmap) {
    $stream = New-Object System.IO.MemoryStream
    $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
    return $stream.ToArray()
}

$files = [ordered]@{}

# ---- hearts (9x9) --------------------------------------------------------------------------------------------
$shape = @(
    ".##...##.",
    "####.####",
    "#########",
    "#########",
    ".#######.",
    "..#####..",
    "...###...",
    "....#....",
    "........."
)
function Inside([int]$x, [int]$y) { return $y -ge 0 -and $y -lt 9 -and $x -ge 0 -and $x -lt 9 -and $shape[$y][$x] -eq '#' }
function IsEdge([int]$x, [int]$y) {
    return (Inside $x $y) -and (-not (Inside ($x - 1) $y) -or -not (Inside ($x + 1) $y) -or -not (Inside $x ($y - 1)) -or -not (Inside $x ($y + 1)))
}
function Heart([string]$kind, [bool]$blink) {
    $img = NewImage 9 9
    $outline = if ($blink) { Color "#e9fcff" } else { Color "#0a1c2e" }
    $top = Color "#d8faff"; $bottom = Color "#0098d6"
    for ($y = 0; $y -lt 9; $y++) {
        for ($x = 0; $x -lt 9; $x++) {
            if (-not (Inside $x $y)) { continue }
            if (IsEdge $x $y) {
                if ($kind -eq "container") { $img.SetPixel($x, $y, $outline) }
                elseif ($kind -eq "full" -or $x -le 4) { $img.SetPixel($x, $y, $outline) }
                continue
            }
            if ($kind -eq "container") { $img.SetPixel($x, $y, (Color "#0c1f33" 170)); continue }
            if ($kind -eq "half" -and $x -gt 4) { continue }
            $fill = Mix $top $bottom ($y / 7.0)
            if (($x -eq 1 -or $x -eq 2) -and $y -eq 2) { $fill = Color "#ffffff" }
            $img.SetPixel($x, $y, $fill)
        }
    }
    return PngBytes $img
}
$heart = "assets/minecraft/textures/gui/sprites/hud/heart"
$files["$heart/container.png"] = Heart "container" $false
$files["$heart/container_blinking.png"] = Heart "container" $true
$files["$heart/full.png"] = Heart "full" $false
$files["$heart/full_blinking.png"] = Heart "full" $true
$files["$heart/half.png"] = Heart "half" $false
$files["$heart/half_blinking.png"] = Heart "half" $true

# ---- hotbar (182x22) and selection (24x23) ----------------------------------------------------------------
$hotbar = NewImage 182 22
$g = [System.Drawing.Graphics]::FromImage($hotbar)
$g.FillRectangle((New-Object System.Drawing.SolidBrush (Color "#0c1f33" 190)), 0, 0, 182, 22)
$edge = New-Object System.Drawing.Pen (Color "#7cebff" 150)
$g.DrawRectangle($edge, 0, 0, 181, 21)
$g.Dispose()
for ($i = 0; $i -lt 9; $i++) {
    $x0 = 1 + 20 * $i
    for ($x = $x0 + 1; $x -le $x0 + 18; $x++) {
        for ($y = 2; $y -le 19; $y++) { $hotbar.SetPixel($x, $y, (Color "#1a3a57" 150)) }
        $hotbar.SetPixel($x, 2, (Color "#b8f4ff" 90))
    }
    if ($i -gt 0) { for ($y = 2; $y -le 19; $y++) { $hotbar.SetPixel($x0, $y, (Color "#7cebff" 70)) } }
}
for ($x = 1; $x -le 180; $x++) { $hotbar.SetPixel($x, 1, (Color "#b8f4ff" 120)) }
$files["assets/minecraft/textures/gui/sprites/hud/hotbar.png"] = PngBytes $hotbar

$selection = NewImage 24 23
$bright = Color "#b8f4ff"; $glow = Color "#00cfff" 150
for ($x = 0; $x -lt 24; $x++) { for ($y = 0; $y -lt 23; $y++) {
    $ring = [Math]::Min([Math]::Min($x, 23 - $x), [Math]::Min($y, 22 - $y))
    if ($ring -eq 0) { $selection.SetPixel($x, $y, $glow) }
    elseif ($ring -eq 1) { $selection.SetPixel($x, $y, $bright) }
} }
foreach ($corner in @(@(1, 1), @(22, 1), @(1, 21), @(22, 21))) { $selection.SetPixel($corner[0], $corner[1], (Color "#ffffff")) }
$files["assets/minecraft/textures/gui/sprites/hud/hotbar_selection.png"] = PngBytes $selection

# ---- boss bars (182x5), every vanilla colour keeps its meaning with a frosted-glass look ----------------------
$bossColors = [ordered]@{ pink = "#ff7ad9"; blue = "#3fc8ff"; red = "#ff5468"; green = "#5df28a"; yellow = "#ffd84d"; purple = "#b27cff"; white = "#eef8ff" }
foreach ($name in $bossColors.Keys) {
    $base = Color $bossColors[$name]
    $background = NewImage 182 5
    $progress = NewImage 182 5
    for ($x = 0; $x -lt 182; $x++) { for ($y = 0; $y -lt 5; $y++) {
        $border = $x -eq 0 -or $x -eq 181 -or $y -eq 0 -or $y -eq 4
        $background.SetPixel($x, $y, $(if ($border) { Color "#7cebff" 160 } else { Color "#0c1f33" 200 }))
        $shade = switch ($y) { 0 { Mix $base (Color "#ffffff") 0.55 } 1 { Mix $base (Color "#ffffff") 0.25 } 4 { Mix $base (Color "#000000") 0.35 } default { $base } }
        $progress.SetPixel($x, $y, $shade)
    } }
    $files["assets/minecraft/textures/gui/sprites/boss_bar/${name}_background.png"] = PngBytes $background
    $files["assets/minecraft/textures/gui/sprites/boss_bar/${name}_progress.png"] = PngBytes $progress
}

# ---- pack.mcmeta and icon ----------------------------------------------------------------------------------
$meta = @"
{
  "pack": {
    "pack_format": 46,
    "supported_formats": { "min_inclusive": 46, "max_inclusive": 75 },
    "min_format": 46,
    "max_format": 75,
    "description": "VYRN UI\nIce hotbar, hearts and boss bars"
  }
}
"@
$files["pack.mcmeta"] = [System.Text.Encoding]::UTF8.GetBytes($meta)
$icon = NewImage 64 64
$g = [System.Drawing.Graphics]::FromImage($icon)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.FillRectangle((New-Object System.Drawing.Drawing2D.LinearGradientBrush((New-Object System.Drawing.Point 0, 0),
    (New-Object System.Drawing.Point 64, 64), (Color "#0c2a45"), (Color "#071321"))), 0, 0, 64, 64)
$font = New-Object System.Drawing.Font("Segoe UI", 30, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$format = New-Object System.Drawing.StringFormat
$format.Alignment = [System.Drawing.StringAlignment]::Center; $format.LineAlignment = [System.Drawing.StringAlignment]::Center
$g.DrawString("V", $font, (New-Object System.Drawing.SolidBrush (Color "#b8f4ff")), (New-Object System.Drawing.RectangleF 0, 0, 64, 64), $format)
$g.Dispose()
$files["pack.png"] = PngBytes $icon

function WriteZip([string]$path, $entries) {
    $temporary = "$path.tmp"
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    $stream = [System.IO.File]::Open($temporary, [System.IO.FileMode]::CreateNew)
    $archive = New-Object System.IO.Compression.ZipArchive($stream, [System.IO.Compression.ZipArchiveMode]::Create)
    foreach ($name in $entries.Keys) {
        $entry = $archive.CreateEntry($name, [System.IO.Compression.CompressionLevel]::Optimal)
        # Fixed timestamps so the zip only changes when its content does (the launcher compares bytes).
        $entry.LastWriteTime = [DateTimeOffset]::new(2026, 11, 22, 0, 0, 0, [TimeSpan]::Zero)
        $out = $entry.Open(); $bytes = $entries[$name]; $out.Write($bytes, 0, $bytes.Length); $out.Dispose()
    }
    $archive.Dispose(); $stream.Dispose()
    Move-Item -LiteralPath $temporary -Destination $path -Force
}
WriteZip (Join-Path $packDir "VYRN UI.zip") $files
Write-Host "Wrote VYRN UI.zip ($($files.Count) files)"

# ---- Ultra Smooth PvP: add min_format/max_format so 1.21.9+ loads it ----------------------------------------
foreach ($zip in @((Join-Path $packDir "Ultra Smooth PvP.zip"), (Join-Path $repoRoot "UltraSmoothPvP\dist\Ultra Smooth PvP.zip"))) {
    if (-not (Test-Path -LiteralPath $zip)) { continue }
    $read = [System.IO.Compression.ZipFile]::OpenRead($zip)
    $entries = [ordered]@{}
    foreach ($entry in $read.Entries) {
        if ($entry.FullName.EndsWith("/")) { continue }
        $in = $entry.Open(); $ms = New-Object System.IO.MemoryStream; $in.CopyTo($ms); $in.Dispose()
        $entries[$entry.FullName] = $ms.ToArray()
    }
    $read.Dispose()
    $text = [System.Text.Encoding]::UTF8.GetString($entries["pack.mcmeta"])
    if ($text -notmatch '"min_format"') {
        $text = $text -replace '("supported_formats":\s*\{[^}]*\})', ('$1,' + "`n    `"min_format`": 46,`n    `"max_format`": 75")
        $entries["pack.mcmeta"] = [System.Text.Encoding]::UTF8.GetBytes($text)
        WriteZip $zip $entries
        Write-Host "Updated pack.mcmeta in $zip"
    }
}

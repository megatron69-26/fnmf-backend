param(
    [Parameter(Mandatory = $true)][string]$SourcePath,
    [Parameter(Mandatory = $true)][int]$StartLine,
    [Parameter(Mandatory = $true)][int]$EndLine,
    [Parameter(Mandatory = $true)][string]$Title,
    [Parameter(Mandatory = $true)][string]$OutputPath
)

Add-Type -AssemblyName System.Drawing

$resolvedSource = (Resolve-Path -LiteralPath $SourcePath).Path
$allLines = Get-Content -LiteralPath $resolvedSource -Encoding UTF8

if ($StartLine -lt 1 -or $EndLine -lt $StartLine -or $EndLine -gt $allLines.Count) {
    throw "Invalid line range $StartLine..$EndLine for $resolvedSource ($($allLines.Count) lines)."
}

$font = New-Object System.Drawing.Font('Consolas', 17, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$titleFont = New-Object System.Drawing.Font('Segoe UI Semibold', 24, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$metaFont = New-Object System.Drawing.Font('Segoe UI', 15, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$lineHeight = 27
$headerHeight = 86
$padding = 24
$width = 1900
$height = $headerHeight + (($EndLine - $StartLine + 1) * $lineHeight) + $padding

$bitmap = New-Object System.Drawing.Bitmap($width, $height)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::ClearTypeGridFit
$graphics.Clear([System.Drawing.ColorTranslator]::FromHtml('#0D1117'))

$headerBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#161B22'))
$graphics.FillRectangle($headerBrush, 0, 0, $width, $headerHeight)

$titleBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#F0F6FC'))
$metaBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#8B949E'))
$lineNumberBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#6E7681'))
$codeBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#C9D1D9'))
$rulePen = New-Object System.Drawing.Pen([System.Drawing.ColorTranslator]::FromHtml('#30363D'))

$graphics.DrawString($Title, $titleFont, $titleBrush, 24, 12)
$sourceName = [System.IO.Path]::GetFileName($resolvedSource)
$graphics.DrawString("$sourceName  |  lines $StartLine-$EndLine", $metaFont, $metaBrush, 25, 51)
$graphics.DrawLine($rulePen, 0, $headerHeight - 1, $width, $headerHeight - 1)

$y = $headerHeight + 10
for ($lineNumber = $StartLine; $lineNumber -le $EndLine; $lineNumber++) {
    $lineText = $allLines[$lineNumber - 1].Replace("`t", '    ')
    $graphics.DrawString($lineNumber.ToString().PadLeft(4), $font, $lineNumberBrush, 20, $y)
    $graphics.DrawString($lineText, $font, $codeBrush, 92, $y)
    $y += $lineHeight
}

$outputDirectory = Split-Path -Parent $OutputPath
if ($outputDirectory) {
    [System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
}

$bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)

$rulePen.Dispose()
$headerBrush.Dispose()
$titleBrush.Dispose()
$metaBrush.Dispose()
$lineNumberBrush.Dispose()
$codeBrush.Dispose()
$font.Dispose()
$titleFont.Dispose()
$metaFont.Dispose()
$graphics.Dispose()
$bitmap.Dispose()

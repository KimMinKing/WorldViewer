param(
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,
    [ValidateRange(1, 2048)]
    [int]$SizeMiB = 50
)

$ErrorActionPreference = "Stop"

$resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
$parent = [IO.Path]::GetDirectoryName($resolvedOutput)
if (-not [string]::IsNullOrEmpty($parent)) {
    [IO.Directory]::CreateDirectory($parent) | Out-Null
}

$strictUtf8 = [Text.UTF8Encoding]::new($false, $true)
$lineCodeUnits = @(
    0x7B2C, 0x4E00, 0x7AE0, 0x20,
    0x6C5F, 0x6E56, 0x8DEF, 0x8FDC, 0x20,
    0x5927, 0x5BB6, 0x597D, 0x3002,
    0x5C71, 0x96E8, 0x6B32, 0x6765, 0xFF0C,
    0x638C, 0x95E8, 0x8FD0, 0x8F6C, 0x5185, 0x529B, 0x3002, 0x0A
)
$lineText = -join ($lineCodeUnits | ForEach-Object { [char]$_ })
$line = $strictUtf8.GetBytes($lineText)
$targetBytes = [long]$SizeMiB * 1MB
$stream = [IO.File]::Open($resolvedOutput, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
try {
    $remaining = $targetBytes
    while ($remaining -ge $line.Length) {
        $stream.Write($line, 0, $line.Length)
        $remaining -= $line.Length
    }
    if ($remaining -gt 0) {
        $safePrefix = [int]$remaining
        while ($safePrefix -gt 0) {
            try {
                $strictUtf8.GetString($line, 0, $safePrefix) | Out-Null
                break
            } catch [Text.DecoderFallbackException] {
                $safePrefix--
            }
        }
        if ($safePrefix -gt 0) {
            $stream.Write($line, 0, $safePrefix)
        }
        $padding = [int]$remaining - $safePrefix
        if ($padding -gt 0) {
            $stream.Write(([Text.Encoding]::ASCII.GetBytes("x" * $padding)), 0, $padding)
        }
    }
    $stream.Flush($true)
} finally {
    $stream.Dispose()
}

$file = Get-Item -LiteralPath $resolvedOutput
$hash = Get-FileHash -LiteralPath $resolvedOutput -Algorithm SHA256
[pscustomobject]@{
    Path = $file.FullName
    Bytes = $file.Length
    Sha256 = $hash.Hash.ToLowerInvariant()
}

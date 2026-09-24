param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "..\app\src\main\assets\pinyin")
)

$ErrorActionPreference = "Stop"

$unicodeVersion = "17.0.0"
$sourceUrl = "https://www.unicode.org/Public/$unicodeVersion/ucd/Unihan.zip"
$sourceSha256 = "F7A48B2B545ACFAA77B2D607AE28747404CE02BAEFEE16396C5D2D7A8EF34B5E"
$downloadPath = Join-Path ([IO.Path]::GetTempPath()) ("worldviewer-unihan-" + [Guid]::NewGuid() + ".zip")
$outputPath = Join-Path $OutputDirectory "unihan_kmandarin_17.bin"
$sourceNotePath = Join-Path $OutputDirectory "unihan_kmandarin_17.source.txt"

try {
    Invoke-WebRequest -Uri $sourceUrl -OutFile $downloadPath
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $downloadPath).Hash
    if ($actualHash -ne $sourceSha256) {
        throw "Unihan.zip SHA-256 mismatch. Expected $sourceSha256, got $actualHash"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($downloadPath)
    try {
        $entry = $archive.GetEntry("Unihan_Readings.txt")
        if ($null -eq $entry) { throw "Unihan_Readings.txt is missing from Unihan.zip" }

        $rows = [Collections.Generic.List[object]]::new()
        $values = [Collections.Generic.SortedSet[string]]::new([StringComparer]::Ordinal)
        $reader = [IO.StreamReader]::new($entry.Open(), [Text.Encoding]::UTF8)
        try {
            while (($line = $reader.ReadLine()) -ne $null) {
                if ($line -notmatch '^U\+([0-9A-F]+)\tkMandarin\t(.+)$') { continue }
                $codePoint = [Convert]::ToUInt32($Matches[1], 16)
                $reading = $Matches[2]
                $rows.Add([PSCustomObject]@{ CodePoint = $codePoint; Reading = $reading })
                [void]$values.Add($reading)
            }
        } finally {
            $reader.Dispose()
        }
    } finally {
        $archive.Dispose()
    }

    $orderedRows = @($rows | Sort-Object CodePoint)
    $orderedValues = @($values)
    if ($orderedRows.Count -ne 44348) {
        throw "Unexpected kMandarin entry count: $($orderedRows.Count)"
    }
    if ($orderedValues.Count -gt [uint16]::MaxValue) {
        throw "Reading table exceeds the UInt16 id range"
    }

    $valueIds = [Collections.Generic.Dictionary[string, uint16]]::new([StringComparer]::Ordinal)
    for ($index = 0; $index -lt $orderedValues.Count; $index++) {
        $valueIds.Add($orderedValues[$index], [uint16]$index)
    }

    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $stream = [IO.File]::Create($outputPath)
    $writer = [IO.BinaryWriter]::new($stream, [Text.Encoding]::UTF8, $false)
    try {
        $writer.Write([Text.Encoding]::ASCII.GetBytes("WVKM"))
        $writer.Write([byte]1) # format version
        $writer.Write([byte]0) # reserved flags
        $writer.Write([uint16]$orderedValues.Count)
        $writer.Write([uint32]$orderedRows.Count)
        foreach ($value in $orderedValues) {
            $bytes = [Text.Encoding]::UTF8.GetBytes($value)
            if ($bytes.Length -gt [uint16]::MaxValue) { throw "Reading value is too long: $value" }
            $writer.Write([uint16]$bytes.Length)
            $writer.Write($bytes)
        }
        foreach ($row in $orderedRows) {
            $writer.Write([uint32]$row.CodePoint)
            $writer.Write([uint16]$valueIds[$row.Reading])
        }
    } finally {
        $writer.Dispose()
    }

    $sourceNote = @"
WorldViewer pinyin base data
Unicode version: $unicodeVersion
Source: $sourceUrl
Source SHA-256: $sourceSha256
Property: kMandarin from Unihan_Readings.txt
Generated entries: $($orderedRows.Count)
Generated SHA-256: $((Get-FileHash -Algorithm SHA256 -LiteralPath $outputPath).Hash)
License: Unicode License v3; see ../licenses/unicode_license_v3.txt
"@
    [IO.File]::WriteAllText($sourceNotePath, $sourceNote.Trim() + "`n", [Text.UTF8Encoding]::new($false))

    Get-Item -LiteralPath $outputPath | Select-Object FullName, Length
    Get-Content -Encoding UTF8 -LiteralPath $sourceNotePath
} finally {
    if (Test-Path -LiteralPath $downloadPath) {
        Remove-Item -LiteralPath $downloadPath -Force
    }
}

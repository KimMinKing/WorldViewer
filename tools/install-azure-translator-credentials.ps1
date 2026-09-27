[CmdletBinding()]
param(
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$secretFile = Join-Path $repositoryRoot '.worldviewer-azure.properties'
$localProperties = Join-Path $repositoryRoot 'local.properties'

if (-not (Test-Path -LiteralPath $secretFile)) {
    throw "Missing local secret file: $secretFile"
}

$values = ConvertFrom-StringData ((Get-Content -LiteralPath $secretFile -Encoding UTF8) -join "`n")
$translatorKey = [string]$values.Key1
$translatorRegion = [string]$values.Region
$translatorKey = $translatorKey.Trim()
$translatorRegion = $translatorRegion.Trim()
if ([string]::IsNullOrWhiteSpace($translatorKey) -or [string]::IsNullOrWhiteSpace($translatorRegion)) {
    throw 'Fill Key1 and Region in .worldviewer-azure.properties first.'
}
if ($translatorKey -notmatch '^[A-Za-z0-9._-]+$' -or $translatorRegion -notmatch '^[A-Za-z0-9._-]+$') {
    throw 'Key1 and Region contain unsupported characters.'
}

$sdkDirectory = $null
if (Test-Path -LiteralPath $localProperties) {
    $sdkLine = Get-Content -LiteralPath $localProperties -Encoding UTF8 |
        Where-Object { $_ -match '^sdk\.dir=' } |
        Select-Object -First 1
    if ($sdkLine) {
        # local.properties uses Java-properties escaping (for example C\:\\Android\\Sdk).
        $sdkDirectory = [regex]::Unescape(($sdkLine -replace '^sdk\.dir=', ''))
    }
}
if (-not $sdkDirectory) {
    $sdkDirectory = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
$adbExecutable = Join-Path $sdkDirectory 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adbExecutable)) {
    throw "ADB was not found: $adbExecutable"
}

if (-not $Serial) {
    $connected = @(& $adbExecutable devices | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" })
    if ($connected.Count -ne 1) {
        throw 'Connect exactly one Android device or pass -Serial.'
    }
    $Serial = ($connected[0] -split "`t")[0]
}

$handoff = "Key1=$translatorKey`nRegion=$translatorRegion`n"
$handoff | & $adbExecutable -s $Serial shell "run-as dev.worldviewer sh -c 'umask 077; cat > files/debug-azure-translator.properties'"
if ($LASTEXITCODE -ne 0) {
    throw 'Could not write the debug app handoff file. Install the debug APK first.'
}

& $adbExecutable -s $Serial shell am force-stop dev.worldviewer | Out-Null
& $adbExecutable -s $Serial shell monkey -p dev.worldviewer -c android.intent.category.LAUNCHER 1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Credentials were handed off, but the debug app could not be started.'
}

Write-Host 'Azure Translator credentials were imported into the debug app Keystore.'

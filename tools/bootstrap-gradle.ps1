# Source-only bootstrap. No downloaded program is executed until SHA-256 is verified.
$ErrorActionPreference = 'Stop'
$Root = Split-Path $PSScriptRoot -Parent
$Cache = Join-Path $Root '.tools'
$Version = '8.13'
$Expected = '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'
$Exe = Join-Path $Cache "gradle-$Version\bin\gradle.bat"
if (Test-Path $Exe) { exit 0 }
New-Item -ItemType Directory -Force -Path $Cache | Out-Null
$Zip = Join-Path $Cache "gradle-$Version-bin.zip"
$Staging = Join-Path $Cache ('unpack-' + [Guid]::NewGuid().ToString('N'))
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -UseBasicParsing -Uri "https://services.gradle.org/distributions/gradle-$Version-bin.zip" -OutFile $Zip
    $Actual = (Get-FileHash -Algorithm SHA256 -Path $Zip).Hash.ToLowerInvariant()
    if ($Actual -ne $Expected) { throw 'Gradle SHA-256 mismatch. Refusing to execute.' }
    Expand-Archive -LiteralPath $Zip -DestinationPath $Staging
    Move-Item -LiteralPath (Join-Path $Staging "gradle-$Version") -Destination $Cache
    Write-Host "Installed checksum-verified Gradle $Version under .tools/"
} finally {
    if (Test-Path $Zip) { Remove-Item -LiteralPath $Zip -Force }
    if (Test-Path $Staging) { Remove-Item -LiteralPath $Staging -Recurse -Force }
}

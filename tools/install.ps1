param([string]$Serial, [string]$Adb, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$Root = Split-Path $PSScriptRoot -Parent
$Package = 'com.itaymatza.carcallrouter'
if (-not $env:ANDROID_HOME -and $env:LOCALAPPDATA) {
    $Candidate = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $Candidate) { $env:ANDROID_HOME = $Candidate }
}
if (-not $Adb) {
    $Found = Get-Command adb -ErrorAction SilentlyContinue
    if ($Found) { $Adb = $Found.Source }
    elseif ($env:ANDROID_HOME -and (Test-Path (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'))) {
        $Adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
    } else { throw 'ADB not found. Install Android Studio SDK Platform-Tools, or pass -Adb C:\path\adb.exe.' }
}
if (-not $SkipBuild) {
    if (-not $env:ANDROID_HOME -and -not (Test-Path (Join-Path $Root 'local.properties'))) {
        throw 'Android SDK not configured. Open this project in Android Studio and install SDK Platform 36 and Build-Tools 35.0.0.'
    }
    & (Join-Path $Root 'gradlew.bat') ':app:assembleDebug' ':app:testDebugUnitTest' ':app:lintDebug' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw 'Build or verification failed. Nothing was installed.' }
}
$Apk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }
$DeviceText = & $Adb devices
if ($LASTEXITCODE -ne 0) { throw 'adb devices failed' }
$Online = @($DeviceText | ForEach-Object { if ($_ -match '^(\S+)\s+device$') { $Matches[1] } })
if (-not $Serial) {
    if ($Online.Count -ne 1) { throw "Expected exactly one authorized phone, found $($Online.Count). Unlock and authorize the phone or pass -Serial." }
    $Serial = $Online[0]
}
$AdbPrefix = @('-s', $Serial)
function Invoke-Adb {
    param([string[]]$Command)
    & $Adb @AdbPrefix @Command
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed: $($Command -join ' ')" }
}
$UserId = (Invoke-Adb -Command @('shell', 'am', 'get-current-user') | Out-String).Trim()
if ($UserId -notmatch '^\d+$') { throw 'Cannot determine current Android user' }
Invoke-Adb -Command @('install', '--user', $UserId, '-r', $Apk)
Invoke-Adb -Command @('shell', 'cmd', 'appops', 'set', '--user', $UserId, '--uid', $Package, 'MANAGE_ONGOING_CALLS', 'allow')
Invoke-Adb -Command @('shell', 'cmd', 'appops', 'get', '--user', $UserId, $Package, 'MANAGE_ONGOING_CALLS')
Invoke-Adb -Command @('shell', 'am', 'start', '--user', $UserId, '-n', "$Package/.ui.MainActivity")
Write-Host 'Installed and requested Telecom authorization. In the app: grant permissions, select BMW, test Route now while parked, then enable automation.'
Write-Host 'Keep Samsung Phone as the default dialer. The app status must show Telecom authorization: true.'

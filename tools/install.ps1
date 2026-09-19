param([string]$Serial, [string]$Adb, [string]$ApplicationId = $env:APP_APPLICATION_ID, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$Root = Split-Path $PSScriptRoot -Parent
if (-not $ApplicationId) { $ApplicationId = 'org.carcallrouter.companion' }
$SourceActivity = 'org.carcallrouter.companion.ui.MainActivity'
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
        throw 'Android SDK not configured. Open this project in Android Studio and install Android SDK Platform Cinnamon Bun and Build-Tools 36.0.0.'
    }
    & (Join-Path $Root 'gradlew.bat') "-PAPP_APPLICATION_ID=$ApplicationId" ':app:assembleDebug' ':app:testDebugUnitTest' ':verification:service-tests:run' ':app:lintDebug' '--console=plain'
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
Invoke-Adb -Command @('shell', 'cmd', 'appops', 'set', '--user', $UserId, '--uid', $ApplicationId, 'MANAGE_ONGOING_CALLS', 'allow')
Invoke-Adb -Command @('shell', 'cmd', 'appops', 'get', '--user', $UserId, $ApplicationId, 'MANAGE_ONGOING_CALLS')
Invoke-Adb -Command @('shell', 'am', 'start', '--user', $UserId, '-n', "$ApplicationId/$SourceActivity")
Write-Host 'Installed and requested Telecom authorization. In the app: grant permissions, select a target device, test Route now while parked, then enable automation.'
Write-Host 'The selected system dialer remains in control. The app status must show Telecom authorization: true.'

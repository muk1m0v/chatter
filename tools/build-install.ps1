param(
    [string]$ProjectPath = (Join-Path $PSScriptRoot '..\android'),
    [string]$DeviceSerial,
    [string]$ApplicationId = 'com.mukimov.salom',
    [switch]$Offline,
    [ValidateSet('debug', 'release')][string]$Variant = 'release'
)
$ErrorActionPreference = 'Stop'
$ProjectPath = (Resolve-Path -LiteralPath $ProjectPath).Path
$sdkPath = $env:ANDROID_HOME
if (-not $sdkPath) { $sdkPath = Join-Path $env:USERPROFILE 'AppData\Local\Android\Sdk' }
[IO.File]::WriteAllText((Join-Path $ProjectPath 'local.properties'), ('sdk.dir=' + $sdkPath.Replace('\', '/') + [Environment]::NewLine));
$adbPath = Join-Path $sdkPath 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adbPath)) { throw "ADB not found: $adbPath" }
$deviceLines = & $adbPath devices
if ($LASTEXITCODE -ne 0) { throw 'ADB device check failed' }
$connectedDevices = @($deviceLines | Where-Object { $_ -match '^\S+\s+device\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
if ($DeviceSerial) {
    if ($DeviceSerial -notin $connectedDevices) { throw 'Selected phone is disconnected or USB debugging is not authorized.' }
} else {
    if ($connectedDevices.Count -ne 1) { throw 'Connect and authorize exactly one phone, or specify -DeviceSerial.' }
    $DeviceSerial = $connectedDevices[0]
}
$gradlePath = Join-Path $ProjectPath 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradlePath)) {
    $cachedGradle = @(Get-ChildItem -Path (Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-8.13-bin\*\gradle-8.13\bin\gradle.bat') -ErrorAction SilentlyContinue)
    if (-not $cachedGradle.Count) { throw 'Gradle Wrapper or cached Gradle 8.13 is required.' }
    $gradlePath = $cachedGradle[0].FullName
}
$buildTask = if ($Variant -eq 'release') { 'assembleRelease' } else { 'assembleDebug' }
$buildArgs = @('-p', $ProjectPath, '--no-daemon', '--console=plain', $buildTask)
if ($Offline) { $buildArgs += '--offline' }
$timer = [Diagnostics.Stopwatch]::StartNew()
& $gradlePath @buildArgs
if ($LASTEXITCODE -ne 0) { throw 'Build failed. No APK was installed.' }
$apkPath = Join-Path $ProjectPath "app\build\outputs\apk\$Variant\app-$Variant.apk"
if (-not (Test-Path -LiteralPath $apkPath)) { throw "APK not found: $apkPath" }
& $adbPath -s $DeviceSerial install -r $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK installation failed.' }
$launchResult = & $adbPath -s $DeviceSerial shell am start -W -n "$ApplicationId/.MainActivity" 2>&1
$launchExit = $LASTEXITCODE
$launchResult | Write-Output
if ($launchExit -ne 0 -or ($launchResult -join "`n") -notmatch 'Status: ok') { throw 'Application launch failed.' }
$timer.Stop()
Write-Output "BUILD + INSTALL + LAUNCH PASSED ($([math]::Round($timer.Elapsed.TotalSeconds, 1)) seconds)"

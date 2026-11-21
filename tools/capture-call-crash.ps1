param([string]$OutputDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) 'outputs\call-diagnostics'))
$ErrorActionPreference = 'Stop'
$devices = & adb devices
if (-not ($devices | Select-String '\tdevice$')) { throw 'ADB не видит телефон. Подключи USB-отладку и повтори.' }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $OutputDirectory "android-call-$stamp.log"
$exitInfo = Join-Path $OutputDirectory "android-exit-$stamp.txt"
& adb logcat -d -v threadtime > $log
& adb shell dumpsys activity exit-info com.mukimov.salom > $exitInfo
Write-Output "Logcat: $log"
Write-Output "Crash history: $exitInfo"

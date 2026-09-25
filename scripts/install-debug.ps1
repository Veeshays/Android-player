#Requires -Version 5.1
<#
.SYNOPSIS
  Safely installs/updates the Pulse debug APK on ONE authorized ADB device.
  Only ever installs this repo's APK. Fails closed on any doubt.
  Never roots, flashes, wipes, reboots, or changes device settings.
#>
[CmdletBinding()]
param(
    [string]$ApkPath = '',
    [string]$ExpectedPackage = 'com.veeshays.pulse.debug',
    [switch]$Launch
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrEmpty($ApkPath)) {
    $here = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
    $ApkPath = (Join-Path $here '..\app\build\outputs\apk\debug\app-debug.apk')
}
$adb = $null
foreach ($c in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
    if ($c) {
        $p = Join-Path $c 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $p) { $adb = $p; break }
    }
}
if (-not $adb) { Write-Host '[FAIL] adb not found (set ANDROID_HOME)'; exit 1 }

$ApkPath = [System.IO.Path]::GetFullPath($ApkPath)
if (!(Test-Path -LiteralPath $ApkPath)) { Write-Host "[FAIL] APK missing: $ApkPath"; exit 1 }
Write-Host "APK: $ApkPath"

& $PSScriptRoot\verify-apk.ps1 -ApkPath $ApkPath -ExpectedPackage $ExpectedPackage
if ($LASTEXITCODE -ne 0) { Write-Host '[FAIL] APK verification failed — refusing to install'; exit 1 }

$devices = (& $adb devices) | Where-Object { $_ -match "`tdevice$" }
if ($devices.Count -eq 0) { Write-Host '[FAIL] no authorized ADB device (adb devices is empty)'; exit 1 }
if ($devices.Count -gt 1) {
    Write-Host '[FAIL] multiple devices — connect exactly one:'
    $devices | ForEach-Object { Write-Host "  $_" }
    exit 1
}
Write-Host "Device: $($devices[0])"
Write-Host "Package: $ExpectedPackage (update only; existing data preserved)"

& $adb install -r $ApkPath
if ($LASTEXITCODE -ne 0) { Write-Host '[FAIL] adb install failed'; exit 1 }
Write-Host '[PASS] installed/updated'

if ($Launch) {
    & $adb shell am start -n "$ExpectedPackage/com.veeshays.pulse.ui.MainActivity" | Out-Null
    if ($LASTEXITCODE -ne 0) { Write-Host '[FAIL] launch failed'; exit 1 }
    Write-Host '[PASS] launched'
}
Write-Host 'RESULT: PASS'

#Requires -Version 5.1
<#
.SYNOPSIS
  Optional: removes ONLY com.veeshays.pulse.debug from the connected device.
  Never touches any other package.
#>
[CmdletBinding()]
param([string]$ExpectedPackage = 'com.veeshays.pulse.debug')

$ErrorActionPreference = 'Stop'
$adb = $null
foreach ($c in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
    if ($c) {
        $p = Join-Path $c 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $p) { $adb = $p; break }
    }
}
if (-not $adb) { Write-Host '[FAIL] adb not found (set ANDROID_HOME)'; exit 1 }

$devices = (& $adb devices) | Where-Object { $_ -match "`tdevice$" }
if ($devices.Count -eq 0) { Write-Host '[FAIL] no authorized ADB device'; exit 1 }
if ($devices.Count -gt 1) { Write-Host '[FAIL] multiple devices — connect exactly one'; exit 1 }

& $adb uninstall $ExpectedPackage
if ($LASTEXITCODE -ne 0) { Write-Host '[FAIL] uninstall failed'; exit 1 }
Write-Host "[PASS] uninstalled $ExpectedPackage"

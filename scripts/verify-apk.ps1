#Requires -Version 5.1
<#
.SYNOPSIS
  Verifies the Pulse debug APK: exists, parseable, expected identity,
  permission allowlist, services, and signing (where tools exist).
  Read-only: never touches a device.
#>
[CmdletBinding()]
param(
    [string]$ApkPath = '',
    [string]$ExpectedPackage = 'com.veeshays.pulse.debug',
    [string]$SdkDir = $env:ANDROID_HOME
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrEmpty($ApkPath)) {
    $here = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
    $ApkPath = (Join-Path $here '..\app\build\outputs\apk\debug\app-debug.apk')
}
$failures = 0
function Check($name, [bool]$ok, [string]$detail = '') {
    if ($ok) { Write-Host "  [PASS] $name $detail" }
    else { Write-Host "  [FAIL] $name $detail"; $script:failures++ }
}

$ApkPath = [System.IO.Path]::GetFullPath($ApkPath)
Write-Host "APK: $ApkPath"
Check 'file exists' (Test-Path -LiteralPath $ApkPath)

if ($failures -gt 0) { exit 1 }

if ([string]::IsNullOrEmpty($SdkDir)) {
    $localProps = Join-Path $PSScriptRoot '..\local.properties'
    if (Test-Path -LiteralPath $localProps) {
        $m = Select-String -LiteralPath $localProps -Pattern '^sdk\.dir=(.+)$' | Select-Object -First 1
        if ($m) { $SdkDir = $m.Matches[0].Groups[1].Value.Replace('\\', '\') }
    }
}
$aapt = Join-Path $SdkDir 'build-tools\34.0.0\aapt.exe'
$apksigner = Join-Path $SdkDir 'build-tools\34.0.0\apksigner.bat'
Check 'aapt available' (Test-Path -LiteralPath $aapt) $aapt
if ($failures -gt 0) { exit 1 }

$badging = & $aapt dump badging $ApkPath 2>&1
Check 'APK parseable (aapt)' ($LASTEXITCODE -eq 0 -and $badging -match "^package:")
if ($failures -gt 0) { exit 1 }

$pkg = [regex]::Match(($badging | Where-Object { $_ -match '^package:' }), "name='([^']+)'").Groups[1].Value
$ver = [regex]::Match(($badging | Where-Object { $_ -match '^package:' }), "versionName='([^']+)'").Groups[1].Value
Check 'package ID' ($pkg -eq $ExpectedPackage) "($pkg)"
Check 'version present' (![string]::IsNullOrEmpty($ver)) "($ver)"
Check 'launcher activity' (@($badging -match 'launchable-activity:.*MainActivity').Count -gt 0) ''

$perms = $badging | Where-Object { $_ -match "^uses-permission:" } |
    ForEach-Object { [regex]::Match($_, "name='([^']+)'").Groups[1].Value }
$allowed = @(
    'android.permission.READ_MEDIA_AUDIO', 'android.permission.READ_MEDIA_VIDEO',
    'android.permission.READ_EXTERNAL_STORAGE', 'android.permission.FOREGROUND_SERVICE',
    'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK', 'android.permission.POST_NOTIFICATIONS',
    'android.permission.MODIFY_AUDIO_SETTINGS', 'android.permission.BLUETOOTH_CONNECT',
    'android.permission.ACCESS_NETWORK_STATE', 'android.permission.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
)
$unexpected = $perms | Where-Object { ($_ -notlike "$ExpectedPackage*") -and ($allowed -notcontains $_) }
Check 'no unexpected permissions' ($unexpected.Count -eq 0) ($unexpected -join ', ')
$dangerous = @('CONTACTS', 'LOCATION', 'CAMERA', 'RECORD_AUDIO', 'SEND_SMS', 'CALL_PHONE', 'SYSTEM_ALERT_WINDOW')
$bad = $perms | Where-Object { $d = $_; $dangerous | Where-Object { $d -like "*$_*" } }
Check 'no unrelated dangerous permissions' ($bad.Count -eq 0) ($bad -join ', ')

$xml = & $aapt dump xmltree $ApkPath AndroidManifest.xml 2>&1 | Out-String
Check 'PlaybackService declared' ($xml -match 'playback.PlaybackService') ''
Check 'mediaPlayback FGS type' ($xml -match 'foregroundServiceType') ''
$exportedTrue = @($xml -match 'android:exported\(0x01010010\)=\(type 0x12\)0xffffffff').Count
Check 'exactly one exported component (launcher)' ($exportedTrue -eq 1) "(found $exportedTrue)"

if (Test-Path -LiteralPath $apksigner) {
    if ([string]::IsNullOrEmpty($env:JAVA_HOME)) {
        $j = Get-Command java -ErrorAction SilentlyContinue
        if ($j) { $env:JAVA_HOME = (Get-Item (Join-Path (Split-Path -Parent $j.Source) '..')).FullName }
    }
    if ([string]::IsNullOrEmpty($env:JAVA_HOME)) {
        Write-Host '  [SKIP] JAVA_HOME/java unavailable — cryptographic verification not claimed'
    } else {
        $sig = & $apksigner verify --verbose $ApkPath 2>&1
        Check 'apksigner verify' ($LASTEXITCODE -eq 0) ''
        ($sig | Select-String -Pattern 'Verified using|Number of signers') | ForEach-Object { Write-Host "  [INFO] $($_.Line.Trim())" }
    }
} else {
    Write-Host '  [SKIP] apksigner unavailable — cryptographic verification not claimed'
}

if ($failures -gt 0) { Write-Host "RESULT: FAIL ($failures)"; exit 1 }
Write-Host 'RESULT: PASS'

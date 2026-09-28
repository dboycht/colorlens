# colorlens dev helper: device check / build / install / launch / log / screenshot.
#
# Why this file exists: during on-device work the same adb incantations get
# retyped dozens of times, and logcat/screencap flags are easy to get wrong.
# One switch per task instead.
#
# Usage (from anywhere):
#   powershell -File tools\dev.ps1 -Check          # device, android version, density
#   powershell -File tools\dev.ps1 -Build          # assembleDebug + unit tests
#   powershell -File tools\dev.ps1 -Install        # build + install debug variant
#   powershell -File tools\dev.ps1 -Install -Release   # install signed release APK
#   powershell -File tools\dev.ps1 -Launch         # start the main activity
#   powershell -File tools\dev.ps1 -Log -Fresh     # clear logcat, then follow
#   powershell -File tools\dev.ps1 -Shot           # screencap into _scratch\shots\
#   powershell -File tools\dev.ps1 -Dump           # UI text+bounds -> _scratch\shots\ui.txt
#   powershell -File tools\dev.ps1 -Tap 540,1980   # tap a device pixel (then dump)
#   powershell -File tools\dev.ps1 -All            # install + launch + shot
#   powershell -File tools\dev.ps1 -Uninstall      # remove both variants
#
# NOTE: pure ASCII on purpose. PowerShell 5.1 reads a BOM-less .ps1 using the
# system ANSI codepage; CJK inside the file can break parsing. Keep comments
# English; device-side strings are passed through as UTF-8 by adb.

[CmdletBinding()]
param(
    [switch]$Check,
    [switch]$Build,
    [switch]$Install,
    [switch]$Launch,
    [switch]$Log,
    [switch]$Fresh,
    [switch]$Shot,
    [switch]$All,
    [switch]$Uninstall,
    [switch]$Release,
    [switch]$Dump,
    [string]$Tap = '',
    [string]$ShotName = '',
    [int]$LogSeconds = 0
)

$ErrorActionPreference = 'Stop'

$scriptPath  = $MyInvocation.MyCommand.Path
$scriptDir   = Split-Path -Parent $scriptPath
$projectRoot = (Resolve-Path (Join-Path $scriptDir '..')).Path

$sdk = 'D:\Program\Android\SDK'
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

$jdkHome = 'C:\Program Files\Microsoft\jdk-21.0.8.9-hotspot'
$gradleDist = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.13-bin" -Directory -ErrorAction SilentlyContinue |
    Select-Object -First 1
$gradle = if ($gradleDist) { Join-Path $gradleDist.FullName 'gradle-8.13\bin\gradle.bat' } else { $null }

$logTag = 'ColorLensProbe'
$pkgRelease = 'com.dboycht.colorlens'
$pkgDebug   = 'com.dboycht.colorlens.debug'
$pkg = if ($Release) { $pkgRelease } else { $pkgDebug }
$apkDebug   = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$apkRelease = Join-Path $projectRoot 'app\build\outputs\apk\release\colorlens-release.apk'
$shotDir    = Join-Path $projectRoot '_scratch\shots'

function Invoke-Gradle([string[]]$Tasks) {
    if (-not $gradle -or -not (Test-Path $gradle)) { throw "gradle not found; check %USERPROFILE%\.gradle\wrapper\dists" }
    $env:JAVA_HOME = $jdkHome
    & $gradle -p $projectRoot @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "gradle failed with exit code $LASTEXITCODE" }
}

function Get-Device {
    $devices = & $adb devices | Select-String 'device$' | ForEach-Object { ($_ -split '\s+')[0] }
    if (-not $devices) { throw 'no device attached (adb devices is empty)' }
    return $devices[0]
}

function Invoke-Install {
    if ($Release) {
        if (-not (Test-Path $apkRelease)) { throw "release APK not found: $apkRelease (run tools\sign-apk.ps1)" }
        $apk = $apkRelease
    } else {
        Invoke-Gradle @('assembleDebug')
        $apk = $apkDebug
    }
    if (-not (Test-Path $apk)) { throw "APK not found: $apk" }
    # adb install streams and can time out on large APKs; push + pm install is reliable.
    $remote = '/data/local/tmp/colorlens.apk'
    & $adb push $apk $remote
    if ($LASTEXITCODE -ne 0) { throw 'adb push failed' }
    & $adb shell pm install -r $remote
    if ($LASTEXITCODE -ne 0) { throw 'pm install failed' }
    & $adb shell rm -f $remote | Out-Null
    Write-Host "installed $apk as $pkg"
}

function Invoke-Launch {
    & $adb shell am start -n "$pkg/com.dboycht.colorlens.MainActivity"
}

function Invoke-Shot {
    if (-not (Test-Path $shotDir)) { New-Item -ItemType Directory -Force -Path $shotDir | Out-Null }
    $name = if ($ShotName) { $ShotName } else { 'shot-' + (Get-Date -Format 'yyyyMMdd-HHmmss') }
    $remote = "/sdcard/$name.png"
    & $adb shell screencap -p $remote
    & $adb pull $remote (Join-Path $shotDir "$name.png")
    & $adb shell rm -f $remote | Out-Null
    Write-Host "screenshot: $(Join-Path $shotDir "$name.png")"
}

function Invoke-Dump {
    # uiautomator's XML is one enormous line, and the console cannot print CJK on a
    # GBK host anyway. Reduce it to "bounds [CLICK] text" and write it as UTF-8 so
    # the agent can read a screen it cannot see.
    $remote = '/sdcard/colorlens-ui.xml'
    & $adb shell uiautomator dump $remote | Out-Null
    if (-not (Test-Path $shotDir)) { New-Item -ItemType Directory -Force -Path $shotDir | Out-Null }
    $localXml = Join-Path $shotDir 'ui.xml'
    & $adb pull $remote $localXml | Out-Null
    & $adb shell rm -f $remote | Out-Null
    [xml]$xml = Get-Content -Raw -Encoding UTF8 $localXml
    $lines = @()
    foreach ($node in $xml.SelectNodes('//node')) {
        $text = $node.text
        $desc = $node.'content-desc'
        if (-not $text -and -not $desc) { continue }
        $label = if ($text) { $text } else { '[desc] ' + $desc }
        $click = if ($node.clickable -eq 'true') { ' CLICK' } else { '' }
        $check = if ($node.checkable -eq 'true') { ' checked=' + $node.checked } else { '' }
        $lines += ('{0}{1}{2}  {3}' -f $node.bounds, $click, $check, $label)
    }
    $textFile = Join-Path $shotDir 'ui.txt'
    $lines | Set-Content -Encoding UTF8 $textFile
    Write-Host "ui text: $textFile ($($lines.Count) labels)"
}

function Invoke-Tap([string]$coords) {
    # -Tap "540,1980"  (device pixels, as printed in the dump's bounds)
    $parts = $coords -split ','
    if ($parts.Count -ne 2) { throw '-Tap needs "x,y"' }
    & $adb shell input tap $parts[0] $parts[1]
    Start-Sleep -Milliseconds 700
}

function Invoke-Check {
    $dev = Get-Device
    Write-Host "device: $dev"
    & $adb shell getprop ro.product.model
    & $adb shell getprop ro.build.version.release
    & $adb shell getprop ro.build.version.sdk
    & $adb shell wm size
    & $adb shell wm density
}

function Invoke-Log {
    if ($Fresh) { & $adb logcat -c }
    if ($LogSeconds -gt 0) {
        $job = Start-Job -ScriptBlock {
            param($adbPath, $tag, $secs)
            & $adbPath logcat -s $tag
        } -ArgumentList $adb, $logTag
        Start-Sleep -Seconds $LogSeconds
        Stop-Job $job | Out-Null
        Receive-Job $job
        Remove-Job $job -Force | Out-Null
    } else {
        & $adb logcat -s $logTag
    }
}

if ($Check)   { Invoke-Check }
if ($Build)   { Invoke-Gradle @('testDebugUnitTest', 'assembleDebug') }
if ($Install) { Invoke-Install }
if ($Launch)  { Invoke-Launch }
if ($Shot)    { Invoke-Shot }
if ($Dump)    { Invoke-Dump }
if ($Tap)     { Invoke-Tap $Tap }
if ($Log)     { Invoke-Log }
if ($Uninstall) {
    & $adb uninstall $pkgRelease | Out-Null
    & $adb uninstall $pkgDebug | Out-Null
    Write-Host 'uninstalled both variants'
}
if ($All) {
    Invoke-Install
    Invoke-Launch
    Start-Sleep -Seconds 2
    Invoke-Shot
}
if (-not ($Check -or $Build -or $Install -or $Launch -or $Log -or $Shot -or $All -or $Uninstall -or $Dump -or $Tap)) {
    Write-Host 'nothing to do; pass one of -Check -Build -Install -Launch -Log -Shot -Dump -Tap -All -Uninstall'
}

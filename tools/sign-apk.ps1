# Sign an APK with the project's release key.
#
# Why this file exists: `assembleRelease` produces an *unsigned* APK, which cannot
# be installed by tapping it, and a release that people install must carry a key
# that stays stable across versions -- Android treats a different signature as a
# different application, so switching keys later forces every user to uninstall.
#
# The key itself (`keystore/colorlens-release.jks`) and its passwords
# (`keystore/keystore.properties`) are deliberately *not* in version control:
# see `.gitignore` and DEVELOPMENT.md. Back both files up; without them a future
# release cannot update an installed copy.
#
# Usage:
#   .\tools\sign-apk.ps1
#
# NOTE: pure ASCII on purpose (PowerShell 5.1 reads BOM-less .ps1 as the system
# ANSI codepage, which garbles non-ASCII and can break parsing).

[CmdletBinding()]
param(
    [string]$In,
    [string]$Out
)

$ErrorActionPreference = 'Stop'

# Resolve the project root from this script's own location. $PSScriptRoot is not
# always populated when invoked with `powershell -File`, so derive it defensively.
$scriptPath  = $MyInvocation.MyCommand.Path
$scriptDir   = Split-Path -Parent $scriptPath
$projectRoot = (Resolve-Path (Join-Path $scriptDir '..')).Path

if (-not $In) {
    $In = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'
}
if (-not $Out) {
    $Out = Join-Path $projectRoot 'app\build\outputs\apk\release\colorlens-release.apk'
}

$sdk       = 'D:\Program\Android\SDK'
$buildTool = Join-Path $sdk 'build-tools\36.1.0'
$zipalign  = Join-Path $buildTool 'zipalign.exe'
$apksigner = Join-Path $buildTool 'apksigner.bat'
$jdkHome   = 'C:\Program Files\Microsoft\jdk-21.0.8.9-hotspot'

# Signing material, read from the git-ignored properties file so no password ever
# lands in a tracked file.
$props = Join-Path $projectRoot 'keystore\keystore.properties'
if (-not (Test-Path $props)) {
    throw "missing signing config: $props  (the release keystore is not in version control; see DEVELOPMENT.md)"
}
$cfg = @{}
foreach ($line in Get-Content $props) {
    if ($line -match '^\s*([A-Za-z]+)\s*=\s*(.+?)\s*$') { $cfg[$matches[1]] = $matches[2] }
}
foreach ($need in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
    if (-not $cfg.ContainsKey($need)) { throw "$props is missing $need" }
}
$keystore = Join-Path $projectRoot ($cfg['storeFile'] -replace '/', '\')

foreach ($tool in @($zipalign, $apksigner, $keystore)) {
    if (-not (Test-Path $tool)) { throw "missing required file: $tool" }
}
if (-not (Test-Path $In)) { throw "input APK not found: $In (run assembleRelease first)" }

$env:JAVA_HOME = $jdkHome

$aligned = Join-Path (Split-Path -Parent $Out) 'colorlens-release-aligned.apk'
foreach ($f in @($aligned, $Out)) { if (Test-Path $f) { Remove-Item $f -Force } }

Write-Host "zipalign: $In"
& $zipalign -p -f 4 $In $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed with exit code $LASTEXITCODE" }

Write-Host "apksigner: $Out"
& $apksigner sign --ks $keystore --ks-key-alias $cfg['keyAlias'] `
    --ks-pass "pass:$($cfg['storePassword'])" --key-pass "pass:$($cfg['keyPassword'])" `
    --out $Out $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed with exit code $LASTEXITCODE" }

Remove-Item $aligned -Force
$size = [math]::Round((Get-Item $Out).Length / 1MB, 2)
$sha  = (Get-FileHash $Out -Algorithm SHA256).Hash
Write-Host "signed APK: $Out ($size MB)"
Write-Host "sha256: $sha"

# Verify the signature we just produced instead of trusting the exit code.
& $apksigner verify --print-certs $Out
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed with exit code $LASTEXITCODE" }

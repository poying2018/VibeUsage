# VibeUsage one-click publish script
# Usage:
#   powershell -ExecutionPolicy Bypass -File publish.ps1 -Version 2.9.5
# Auto: bump version -> signed release build -> verify signature -> copy to dist/
#       -> git commit/push -> create/update GitHub Release
# Note: this file is intentionally ASCII-only (no Chinese) to avoid
#       PowerShell 5 encoding issues (BOM loss on edits).
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Repo = "poying2018/VibeUsage"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$apk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
$dist = Join-Path $root "dist"
$tag = "v$Version"
$apkName = "VibeUsage-v$Version-release.apk"
$gradleFile = Join-Path $root "app\build.gradle.kts"
$dashboardFile = Join-Path $root "app\src\main\java\ai\vibecafe\usage\ui\DashboardScreen.kt"

# UTF-8 no BOM for file writes (gradle/dashboard sources)
$utf8 = New-Object System.Text.UTF8Encoding($false)

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " VibeUsage publish v$Version" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# 0. Auto bump version (versionCode +1, versionName, APP_VERSION)
Write-Host "[0/7] Bump version -> v$Version ..." -ForegroundColor Yellow
$gradleContent = [System.IO.File]::ReadAllText($gradleFile, $utf8)
$currentCode = if ($gradleContent -match 'versionCode = (\d+)') { [int]$Matches[1] } else { throw "versionCode not found" }
$newCode = $currentCode + 1
$gradleContent = $gradleContent -replace 'versionCode = \d+', "versionCode = $newCode"
$gradleContent = $gradleContent -replace 'versionName = "[^"]+"', "versionName = `"$Version`""
[System.IO.File]::WriteAllText($gradleFile, $gradleContent, $utf8)
$dashContent = [System.IO.File]::ReadAllText($dashboardFile, $utf8)
$dashContent = $dashContent -replace 'APP_VERSION = "v[^"]+"', "APP_VERSION = `"v$Version`""
[System.IO.File]::WriteAllText($dashboardFile, $dashContent, $utf8)
Write-Host "[0/7] versionCode $currentCode -> $newCode, versionName -> $Version" -ForegroundColor Green

# 1. Check signing config
if (-not (Test-Path (Join-Path $root "keystore.properties"))) {
    throw "Missing keystore.properties! Release must be signed. See keystore.properties.example"
}
Write-Host "[1/7] Signing config OK" -ForegroundColor Green

# 2. Build signed release
Write-Host "[2/7] Building signed release APK..." -ForegroundColor Yellow
Push-Location $root
try {
    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
    $env:GRADLE_USER_HOME = Join-Path $root ".gradle-home"
    .\gradlew.bat assembleRelease --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
} finally { Pop-Location }
Write-Host "[2/7] Build done" -ForegroundColor Green

# 3. Verify APK signature
Write-Host "[3/7] Verifying APK signature..." -ForegroundColor Yellow
$apksigner = "D:\studio sdk\build-tools\37.0.0\apksigner.bat"
$verifyOut = & $apksigner verify --print-certs $apk 2>&1 | Out-String
if ($verifyOut -notmatch "certificate DN") {
    throw "Signature verification FAILED (unsigned APK): $verifyOut"
}
Write-Host "[3/7] Signature verified (Signer #1)" -ForegroundColor Green

# 4. Copy to dist
Write-Host "[4/7] Copying to dist/..." -ForegroundColor Yellow
New-Item -ItemType Directory -Path $dist -Force | Out-Null
$distApk = Join-Path $dist $apkName
Copy-Item $apk $distApk -Force
$sha = (Get-FileHash $distApk -Algorithm SHA256).Hash
Write-Host "[4/7] $apkName ($([math]::Round((Get-Item $distApk).Length/1MB,2)) MB)" -ForegroundColor Green
Write-Host "      SHA-256: $sha" -ForegroundColor Green

# 5. Git commit & push
Write-Host "[5/7] Git commit & push..." -ForegroundColor Yellow
git -C $root add -A
git -C $root -c user.name="poying2018" -c user.email="poying2018@users.noreply.github.com" commit -m "release: v$Version"
git -C $root push origin main
if ($LASTEXITCODE -ne 0) { throw "git push failed" }
Write-Host "[5/7] Pushed" -ForegroundColor Green

# 6. Create/update GitHub Release
Write-Host "[6/7] Updating GitHub Release..." -ForegroundColor Yellow
# Use cmd wrapper to avoid PowerShell 5 interference with gh non-zero exits
cmd /c "gh release view $tag --repo $Repo >nul 2>nul"
$releaseExists = $LASTEXITCODE -eq 0
if ($releaseExists) {
    $oldAsset = gh release view $tag --repo $Repo --json assets 2>$null | ConvertFrom-Json
    foreach ($a in $oldAsset.assets) {
        gh release delete-asset $tag $a.name --repo $Repo --yes 2>$null
    }
    gh release upload $tag $distApk --repo $Repo --clobber 2>$null
} else {
    gh release create $tag $distApk --repo $Repo --title "VibeUsage v$Version" --notes "VibeUsage v$Version (signed APK)" 2>$null
}
Write-Host "[6/7] Release $tag updated" -ForegroundColor Green

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Done: https://github.com/$Repo/releases/tag/$tag" -ForegroundColor Cyan
Write-Host " APK: $distApk" -ForegroundColor Cyan
Write-Host " SHA-256: $sha" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

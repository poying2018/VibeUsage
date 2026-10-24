# VibeUsage one-click publish script
# Usage:
#   powershell -ExecutionPolicy Bypass -File publish.ps1 -Version 2.9.5
# Auto: bump version -> signed release build -> verify signature -> copy to dist/
#       -> git commit/push -> create/update GitHub Release
#
# Re-run safe: running the same command again after a failure is idempotent.
#   - step 0 skips the bump when versionName already equals -Version
#   - step 5 commits only when there is something to commit, and retries the push
#   - step 6 uploads with --clobber instead of deleting assets first
#   So recovery from a failed push is just: run the same command again.
#
# Transport note: on this machine github.com:443 is unreachable (git-over-HTTPS
#   fails with "Connection was reset") while ssh.github.com and the GitHub API
#   work. This repo has a local url.insteadOf rule routing HTTPS to SSH:
#     git config url."git@github.com:".insteadOf "https://github.com/"
#   Verify with: git fetch origin
#
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

# UTF-8 no BOM for file writes (gradle sources)
$utf8 = New-Object System.Text.UTF8Encoding($false)

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " VibeUsage publish v$Version" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# 0. Auto bump version (versionCode +1, versionName)
#    Skipped when versionName already equals -Version, so re-running after a
#    failure cannot bump the versionCode twice.
#    Note: the dashboard's APP_VERSION now derives from BuildConfig.VERSION_NAME,
#    so there is nothing to rewrite there anymore (the old literal replace was a
#    no-op that silently did nothing while the log still claimed it had bumped it).
Write-Host "[0/7] Bump version -> v$Version ..." -ForegroundColor Yellow
$gradleContent = [System.IO.File]::ReadAllText($gradleFile, $utf8)
$currentCode = if ($gradleContent -match 'versionCode = (\d+)') { [int]$Matches[1] } else { throw "versionCode not found" }
$currentName = if ($gradleContent -match 'versionName = "([^"]+)"') { $Matches[1] } else { "" }
if ($currentName -eq $Version) {
    Write-Host "[0/7] Already at v$Version (versionCode $currentCode) - bump skipped" -ForegroundColor Green
} else {
    $newCode = $currentCode + 1
    $gradleContent = $gradleContent -replace 'versionCode = \d+', "versionCode = $newCode"
    $gradleContent = $gradleContent -replace 'versionName = "[^"]+"', "versionName = `"$Version`""
    [System.IO.File]::WriteAllText($gradleFile, $gradleContent, $utf8)
    Write-Host "[0/7] versionCode $currentCode -> $newCode, versionName -> $Version" -ForegroundColor Green
}

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
# Resolve apksigner from local.properties sdk.dir so this script is not tied to one
# machine's SDK location (it used to hardcode an absolute D:\ path that breaks elsewhere).
$sdkDir = $null
$lp = Join-Path $root "local.properties"
if (Test-Path $lp) {
    $line = Get-Content $lp | Where-Object { $_ -match '^\s*sdk\.dir=' } | Select-Object -First 1
    if ($line) { $sdkDir = ($line -replace '^\s*sdk\.dir=', '').Trim() -replace '\\:', ':' -replace '/', '\' }
}
if (-not $sdkDir) { $sdkDir = $env:ANDROID_HOME }
$apksigner = $null
if ($sdkDir) {
    $btRoot = Join-Path $sdkDir "build-tools"
    if (Test-Path $btRoot) {
        # Pick the highest build-tools version present (36.0.0 beats 35.x)
        $bt = Get-ChildItem $btRoot -Directory |
            Sort-Object { [version](($_.Name -split '-')[0]) } -Descending |
            Select-Object -First 1
        if ($bt) { $apksigner = Join-Path $bt.FullName "apksigner.bat" }
    }
}
if (-not $apksigner -or -not (Test-Path $apksigner)) {
    throw "apksigner not found under sdk.dir=$sdkDir\build-tools. Install build-tools or set sdk.dir in local.properties."
}
Write-Host "      using $apksigner" -ForegroundColor DarkGray
$verifyOut = & $apksigner verify --print-certs $apk 2>&1 | Out-String
if ($verifyOut -notmatch "certificate DN") {
    throw "Signature verification FAILED (unsigned APK): $verifyOut"
}
$certSha = ([regex]::Match($verifyOut, 'certificate SHA-256 digest: ([0-9a-fA-F]+)')).Groups[1].Value
Write-Host "[3/7] Signature verified (Signer #1): $certSha" -ForegroundColor Green

# 4. Copy to dist
Write-Host "[4/7] Copying to dist/..." -ForegroundColor Yellow
New-Item -ItemType Directory -Path $dist -Force | Out-Null
$distApk = Join-Path $dist $apkName
Copy-Item $apk $distApk -Force
$sha = (Get-FileHash $distApk -Algorithm SHA256).Hash
# Sidecar hash file for the app's in-app updater integrity check
$hashFile = "$distApk.sha256"
[System.IO.File]::WriteAllText($hashFile, "$sha  $apkName")
Write-Host "[4/7] $apkName ($([math]::Round((Get-Item $distApk).Length/1MB,2)) MB)" -ForegroundColor Green
Write-Host "      SHA-256: $sha" -ForegroundColor Green

# 5. Git commit & push
Write-Host "[5/7] Git commit & push..." -ForegroundColor Yellow
git -C $root add -A
$pending = git -C $root status --porcelain
if ($pending) {
    git -C $root -c user.name="poying2018" -c user.email="poying2018@users.noreply.github.com" commit -m "release: v$Version"
} else {
    Write-Host "      nothing to commit (already committed) - skipped" -ForegroundColor DarkGray
}
# Push with retries. A transient network failure used to abort the script here and
# leave "committed locally, not pushed, no GitHub Release" behind.
$pushed = $false
for ($try = 1; $try -le 3; $try++) {
    git -C $root push origin main
    if ($LASTEXITCODE -eq 0) { $pushed = $true; break }
    Write-Host "      push attempt $try failed, retrying in 5s..." -ForegroundColor DarkYellow
    Start-Sleep -Seconds 5
}
if (-not $pushed) {
    Write-Host ""
    Write-Host "[5/7] PUSH FAILED - the release was NOT published." -ForegroundColor Red
    Write-Host "      Local commit 'release: v$Version' exists; the remote does not have it." -ForegroundColor Red
    Write-Host "      Check the transport first:  git fetch origin" -ForegroundColor Red
    Write-Host "      (github.com:443 is blocked on this machine, SSH works - see header note.)" -ForegroundColor Red
    Write-Host "      Once the network is back, run this script again with the same" -ForegroundColor Red
    Write-Host "      -Version: it is re-run safe (no double version bump, clobber upload)." -ForegroundColor Red
    exit 1
}
Write-Host "[5/7] Pushed" -ForegroundColor Green

# 6. Create/update GitHub Release
Write-Host "[6/7] Updating GitHub Release..." -ForegroundColor Yellow
# Use cmd wrapper to avoid PowerShell 5 interference with gh non-zero exits
cmd /c "gh release view $tag --repo $Repo >nul 2>nul"
$releaseExists = $LASTEXITCODE -eq 0
if ($releaseExists) {
    # --clobber overwrites assets in place. The old code deleted every asset first,
    # which left the release with no artifacts at all if the upload then failed.
    gh release upload $tag $distApk $hashFile --repo $Repo --clobber 2>$null
} else {
    gh release create $tag $distApk $hashFile --repo $Repo --title "VibeUsage v$Version" --notes "VibeUsage v$Version (signed APK)" 2>$null
}
# Verify the uploaded artifact really is this APK (byte-for-byte), so a silently
# failed/partial upload cannot masquerade as a published release.
$expected = "sha256:$($sha.ToLower())"
$remote = gh release view $tag --repo $Repo --json assets 2>$null | ConvertFrom-Json
$uploaded = $remote.assets | Where-Object { $_.name -eq $apkName }
if (-not $uploaded) {
    Write-Host "[6/7] Release $tag has no asset named $apkName." -ForegroundColor Red
    exit 1
}
if ($uploaded.digest -and $uploaded.digest -ne $expected) {
    Write-Host "[6/7] Asset digest MISMATCH - upload is not the local APK." -ForegroundColor Red
    Write-Host "      remote: $($uploaded.digest)" -ForegroundColor Red
    Write-Host "      local : $expected" -ForegroundColor Red
    exit 1
}
Write-Host "[6/7] Release $tag updated (asset digest verified: $expected)" -ForegroundColor Green

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Done: https://github.com/$Repo/releases/tag/$tag" -ForegroundColor Cyan
Write-Host " APK: $distApk" -ForegroundColor Cyan
Write-Host " SHA-256: $sha" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

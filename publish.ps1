# VibeUsage 一键发布脚本
# 用法:
#   1) 先在 app/build.gradle.kts 更新 versionName/versionCode
#   2) 执行:  powershell -ExecutionPolicy Bypass -File publish.ps1 -Version 2.9.0
#   3) 脚本会: 构建签名 release → 验证签名 → 复制到 dist/
#             → git 提交推送 → 创建/更新 GitHub Release 并上传 APK
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

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " VibeUsage 发布 v$Version" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# 1. 校验签名配置
if (-not (Test-Path (Join-Path $root "keystore.properties"))) {
    throw "缺少 keystore.properties! 发布版本必须签名, 请先配置(参考 keystore.properties.example)"
}
Write-Host "[1/6] 签名配置 OK" -ForegroundColor Green

# 2. 构建签名 release
Write-Host "[2/6] 构建签名 release APK..." -ForegroundColor Yellow
Push-Location $root
try {
    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
    $env:GRADLE_USER_HOME = Join-Path $root ".gradle-home"
    .\gradlew.bat assembleRelease --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败" }
} finally { Pop-Location }
Write-Host "[2/6] 构建完成" -ForegroundColor Green

# 3. 验证 APK 签名
Write-Host "[3/6] 验证 APK 签名..." -ForegroundColor Yellow
$apksigner = "D:\studio sdk\build-tools\37.0.0\apksigner.bat"
$verifyOut = & $apksigner verify --print-certs $apk 2>&1 | Out-String
if ($verifyOut -notmatch "Signer #1 certificate DN") {
    throw "签名验证失败! 产出的是未签名 APK: $verifyOut"
}
Write-Host "[3/6] 签名验证通过 (Signer #1)" -ForegroundColor Green

# 4. 复制到 dist
Write-Host "[4/6] 复制到 dist/..." -ForegroundColor Yellow
New-Item -ItemType Directory -Path $dist -Force | Out-Null
$distApk = Join-Path $dist $apkName
Copy-Item $apk $distApk -Force
$sha = (Get-FileHash $distApk -Algorithm SHA256).Hash
Write-Host "[4/6] $apkName ($([math]::Round((Get-Item $distApk).Length/1MB,2)) MB)" -ForegroundColor Green
Write-Host "      SHA-256: $sha" -ForegroundColor Green

# 5. git 提交推送
Write-Host "[5/6] git 提交并推送..." -ForegroundColor Yellow
git -C $root add -A
git -C $root -c user.name="poying2018" -c user.email="poying2018@users.noreply.github.com" commit -m "release: v$Version"
git -C $root push origin main
if ($LASTEXITCODE -ne 0) { throw "git push 失败" }
Write-Host "[5/6] 已推送" -ForegroundColor Green

# 6. 创建/更新 GitHub Release
Write-Host "[6/6] 更新 GitHub Release..." -ForegroundColor Yellow
$existing = gh release view $tag --repo $Repo 2>&1
if ($LASTEXITCODE -eq 0) {
    # 已存在: 删除旧资产并上传新 APK
    $oldAsset = gh release view $tag --repo $Repo --json assets 2>&1 | ConvertFrom-Json
    foreach ($a in $oldAsset.assets) {
        gh release delete-asset $tag $a.name --repo $Repo --yes
    }
    gh release upload $tag $distApk --repo $Repo --clobber
} else {
    gh release create $tag $distApk --repo $Repo --title "VibeUsage v$Version" --notes "VibeUsage v$Version 发布 (签名 APK)"
}
Write-Host "[6/6] Release $tag 已更新" -ForegroundColor Green

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " ✅ 发布完成: https://github.com/$Repo/releases/tag/$tag" -ForegroundColor Cyan
Write-Host "    APK: $distApk" -ForegroundColor Cyan
Write-Host "    SHA-256: $sha" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

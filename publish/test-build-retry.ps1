# Test for the publish build retry helper.
#
# Drives Invoke-BuildWithRetry with a FAKE gradlew.bat so the retry path can be
# exercised deterministically (no real build, no git, no release).
#
# Scenarios:
#   1. build fails twice, succeeds on the 3rd attempt -> helper returns true
#   2. build always fails -> helper returns false after N attempts
#   3. the retry path really stops the daemons and clears the dex intermediates
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File publish/test-build-retry.ps1

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("vibeusage-publish-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tmp -Force | Out-Null

function New-FakeProject([int]$failuresBeforeSuccess) {
    $proj = Join-Path $tmp ([guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $proj -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $proj "app\build\intermediates\dex\release") -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $proj "app\build\outputs\apk\release") -Force | Out-Null
    # Marker file the fake gradle touches, so we can assert the cleanup ran.
    $marker = Join-Path $proj "app\build\intermediates\dex\release\classes.dex"
    Set-Content -Path $marker -Value "stale dex output" -Encoding ASCII

    $counter = Join-Path $proj "counter.txt"
    Set-Content -Path $counter -Value "0" -Encoding ASCII
    $stopCalls = Join-Path $proj "stop-calls.txt"
    Set-Content -Path $stopCalls -Value "" -Encoding ASCII

    $bat = Join-Path $proj "gradlew.bat"
    @"
@echo off
if "%1"=="--stop" (
    echo x>> "$stopCalls"
    exit /b 0
)
set /p N=< "$counter"
set /a N=N+1
> "$counter" echo %N%
if %N% LEQ $failuresBeforeSuccess (
    echo simulated build failure ^(attempt %N%^)
    exit /b 1
)
echo simulated build success
exit /b 0
"@ | Set-Content -Path $bat -Encoding ASCII
    return @{
        Dir = $proj
        Counter = $counter
        StopCalls = $stopCalls
        Marker = $marker
    }
}

. (Join-Path $root "publish\lib.ps1")

$failed = 0
function Assert-True($cond, $msg) {
    if ($cond) { Write-Host "  PASS: $msg" -ForegroundColor Green }
    else { Write-Host "  FAIL: $msg" -ForegroundColor Red; $script:failed++ }
}

Write-Host "Scenario 1: fails twice, succeeds on the 3rd attempt" -ForegroundColor Cyan
$p = New-FakeProject 2
$ok = Invoke-BuildWithRetry -Root $p.Dir -Attempts 3
Assert-True ($ok -eq $true) "helper reports success"
Assert-True ((Get-Content $p.Counter).Trim() -eq "3") "build was attempted 3 times"
$stops = (Get-Content $p.StopCalls | Where-Object { $_ -ne "" }).Count
Assert-True ($stops -eq 2) "daemons were stopped before each retry (got $stops)"
Assert-True (-not (Test-Path $p.Marker)) "stale dex intermediates were cleared"

Write-Host "Scenario 2: always fails" -ForegroundColor Cyan
$p2 = New-FakeProject 99
$ok2 = Invoke-BuildWithRetry -Root $p2.Dir -Attempts 3
Assert-True ($ok2 -eq $false) "helper reports failure"
Assert-True ((Get-Content $p2.Counter).Trim() -eq "3") "gave up after 3 attempts"

Write-Host "Scenario 3: succeeds on the first attempt (no daemon stop)" -ForegroundColor Cyan
$p3 = New-FakeProject 0
$ok3 = Invoke-BuildWithRetry -Root $p3.Dir -Attempts 3
Assert-True ($ok3 -eq $true) "helper reports success"
Assert-True ((Get-Content $p3.Counter).Trim() -eq "1") "no retry was needed"
$stops3 = (Get-Content $p3.StopCalls | Where-Object { $_ -ne "" }).Count
Assert-True ($stops3 -eq 0) "daemons were not stopped unnecessarily"

Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
if ($failed -eq 0) { Write-Host "ALL PASS" -ForegroundColor Green; exit 0 }
Write-Host "$failed assertion(s) failed" -ForegroundColor Red
exit 1

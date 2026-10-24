# VibeUsage publish helpers (dot-sourced by publish.ps1).
#
# Kept in its own file so the retry logic can be exercised by a test without
# running the whole publish pipeline (which would push and publish for real).
#
# This file is intentionally ASCII-only (no Chinese) to avoid
# PowerShell 5 encoding issues (BOM loss on edits).

function Invoke-BuildWithRetry {
    <#
      Runs `gradlew.bat assembleRelease --no-daemon` and retries on failure.
      .SYNOPSIS
        Builds the signed release APK, recovering from transient failures.
      .PARAMETER Root
        Project root (the folder holding gradlew.bat).
      .PARAMETER Attempts
        How many times to try before giving up.
      .PARAMETER GradleTask
        Gradle task to run (default: assembleRelease).
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [int]$Attempts = 3,
        [string]$GradleTask = "assembleRelease"
    )

    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
    $env:GRADLE_USER_HOME = Join-Path $Root ".gradle-home"

    Push-Location $Root
    try {
        for ($try = 1; $try -le $Attempts; $try++) {
            if ($try -gt 1) {
                # Known failure: minifyReleaseWithR8 aborts with
                #   "classes.dex ... is being used by another process"
                # when a Gradle daemon from an earlier interactive build still
                # holds the previous R8 output. It is not a code problem - the
                # same build succeeds right after the daemons are stopped.
                # So: stop the daemons, drop the stale dex intermediates, retry.
                Write-Host "      retry $try/$Attempts : stopping daemons and clearing locked dex output..." -ForegroundColor DarkYellow
                & ".\gradlew.bat" --stop 2>&1 | Out-Null
                Start-Sleep -Seconds 3
                $dexDir = Join-Path $Root "app\build\intermediates\dex\release"
                if (Test-Path $dexDir) {
                    Remove-Item -Recurse -Force $dexDir -ErrorAction SilentlyContinue
                }
            }
            # Out-Host: the build's console output must go to the host, NOT into
            # the pipeline - otherwise it is captured together with the return
            # value and the caller gets an array instead of a boolean.
            & ".\gradlew.bat" $GradleTask --no-daemon | Out-Host
            if ($LASTEXITCODE -eq 0) { return $true }
            Write-Host "      build attempt $try/$Attempts failed (exit code $LASTEXITCODE)" -ForegroundColor DarkYellow
        }
    } finally {
        Pop-Location
    }
    return $false
}

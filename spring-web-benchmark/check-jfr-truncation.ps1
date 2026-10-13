# =============================================================================
# check-jfr-truncation.ps1 - verify JFR recordings have no truncated stacks (Windows native)
#
# Usage:
#   ./check-jfr-truncation.ps1 <jfr file or directory> [...]
#   e.g. ./check-jfr-truncation.ps1 benchmark-reports/jfr-cpu-hotspot-20260916
#
# Environment:
#   JFR_BIN   path to jfr executable (default: found on PATH, or %JAVA_HOME%\bin\jfr.exe)
#
# Why:
#   The JVM default JFR stack depth is 64 frames. If a recording is started with
#   -XX:StartFlightRecording but WITHOUT -XX:FlightRecorderOptions=stackdepth=N,
#   deep stacks are marked truncated: the dropped part is the OUTER frames
#   (Netty/framework entry -> call chain root), which distorts flame graphs and
#   hot-spot attribution. Note that "jfr print --stack-depth" only limits the
#   PRINT layer and cannot recover data that was already truncated at record time.
#
# Exit codes: 0 = clean; 1 = truncation found; 2 = usage/environment error
# (Messages are ASCII-only so the script also runs on Windows PowerShell 5.1,
#  which reads BOM-less UTF-8 scripts as ANSI.)
# =============================================================================
param(
    [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)][string[]]$Paths
)

$jfr = if ($env:JFR_BIN) { $env:JFR_BIN } else { 'jfr' }
if (-not (Get-Command $jfr -ErrorAction SilentlyContinue)) {
    Write-Host "ERROR: jfr command not found (set JFR_BIN to %JAVA_HOME%\bin\jfr.exe)"
    exit 2
}

$files = @()
foreach ($p in $Paths) {
    if (Test-Path -PathType Container $p) {
        $files += Get-ChildItem -Path $p -Filter *.jfr -File | Sort-Object Name
    }
    elseif (Test-Path -PathType Leaf $p) {
        $files += Get-Item $p
    }
    else {
        Write-Host "SKIP (not found): $p"
    }
}
if ($files.Count -eq 0) {
    Write-Host "ERROR: no .jfr file to check"
    exit 2
}

$badFiles = 0
foreach ($f in $files) {
    $json = (& $jfr print --events jdk.ExecutionSample --json --stack-depth 2048 $f.FullName 2>$null | Out-String)
    $total = ([regex]::Matches($json, '"truncated"\s*:')).Count
    $bad = ([regex]::Matches($json, '"truncated"\s*:\s*true')).Count
    if ($total -eq 0) {
        Write-Host ("{0,-46} no ExecutionSample events (or empty file)" -f $f.Name)
        continue
    }
    $rate = [math]::Round(100.0 * $bad / $total, 2)
    if ($bad -gt 0) {
        Write-Host ("{0,-46} samples={1,-6} truncated={2,-6} ({3}%) TRUNCATED" -f $f.Name, $total, $bad, $rate)
        $badFiles++
    }
    else {
        Write-Host ("{0,-46} samples={1,-6} truncated=0 ({2}%) OK" -f $f.Name, $total, $rate)
    }
}

Write-Host ""
if ($badFiles -gt 0) {
    Write-Host "[FAIL] $badFiles file(s) contain truncated stacks: recording JVM lacked (or used too small) stackdepth"
    Write-Host "       Fix: add -XX:FlightRecorderOptions=stackdepth=1024 to the recorded JVM"
    exit 1
}
Write-Host "[OK] checked $($files.Count) file(s), no truncated stacks"
exit 0

#
# check-netty-leaks.ps1 - detect Netty ByteBuf leak reports in run logs (Windows counterpart of
# scripts/check-netty-leaks.sh).
#
# Usage (all arguments are named; -Paths is ';'-separated):
#   powershell -ExecutionPolicy Bypass -File check-netty-leaks.ps1 -Paths "a.log;b.log;logs\"
#   powershell -ExecutionPolicy Bypass -File check-netty-leaks.ps1 -Run [-Rounds N] [-LogDir DIR] [-Clean] [-ExtraMvnArgs "..."]
#
# -Clean: runs `mvn -o clean test ...`, matching CI (`mvn clean test`). Without it the run reuses
#   target/ classes from a previous build; if those were produced by a different JDK, failures (and
#   even a spurious leak report) can appear that have nothing to do with the current sources.
#
# Why this exists:
#   ResourceLeakDetector only reports leaks in "paranoid" mode, and it logs through
#   java.util.logging (reportTracedLeak / reportUntracedLeak) with a platform-locale date prefix.
#   On zh-CN Windows that prefix is GBK, so naive UTF-8 grep silently misses the report lines.
#   The default record cap is 4 ("leak records were discarded" lines appear in the log), so -Run
#   also passes io.netty.leakDetection.targetRecords=16.
#   -XshowSettings:properties is used to positively prove the level reached the test JVMs.
#
# Log encoding: -Run captures maven output with Tee-Object, which writes UTF-16LE. All reads below
#   therefore detect the encoding from the first bytes; byte-level tools (findstr/grep) cannot be
#   used on such logs.
#
# Two independent failure kinds are reported separately (a maven/test failure is NOT a leak):
#   1) maven exit code != 0  -> build or test failure
#   2) leak report markers   -> Netty ByteBuf leak reports
#
# Exit codes: 0 = clean, 1 = failure (either kind), 2 = usage/environment error
#
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads BOM-less UTF-8 as ANSI, and
#       non-ASCII characters break parsing/quoting.
#
param(
    # All arguments are named on purpose: PowerShell binds unnamed arguments positionally by
    # declaration order, which silently fed a bare log path into -Rounds (int) during testing.
    # -Paths accepts ';'-separated files or directories.
    [string]$Paths = "",
    [switch]$Run,
    [switch]$Clean,
    [int]$Rounds = 1,
    [string]$LogDir = ".",
    [string]$ExtraMvnArgs = ""
)

$ErrorActionPreference = 'Continue'

# PowerShell 5.1 has no -Encoding auto-detect for Get-Content when an encoding is passed
# explicitly, and its own redirection writes UTF-16LE. Detect the encoding from the first bytes.
function Get-LogEncoding {
    param([string]$File)
    $encoding = 'Default'
    try {
        $fs = [System.IO.File]::OpenRead($File)
        $head = New-Object byte[] 2
        $null = $fs.Read($head, 0, 2)
        $fs.Close()
        if (($head[0] -eq 0xFF -and $head[1] -eq 0xFE) -or $head[1] -eq 0x00) { $encoding = 'Unicode' }
        elseif ($head[0] -eq 0xEF -and $head[1] -eq 0xBB) { $encoding = 'UTF8' }
    } catch {
        # fall back to the default encoding
    }
    return $encoding
}

function Get-LogLines {
    param([string]$File)
    $encoding = Get-LogEncoding -File $File
    return @(Get-Content -LiteralPath $File -Encoding $encoding -ErrorAction SilentlyContinue)
}

# Returns a single object (NOT text): any Write-Output inside a function becomes part of its
# return value, so mixing text+bool makes the caller's "if (-not ...)" operate on an array and the
# gate silently passes forever (this was caught by the negative test case).
function Get-LeakCounts {
    param([string]$File)
    $lines = Get-LogLines -File $File
    [pscustomobject]@{
        Traced = @($lines | Where-Object { $_ -match 'reportTracedLeak' }).Count
        Untraced = @($lines | Where-Object { $_ -match 'reportUntracedLeak' }).Count
        Slf4j = @($lines | Where-Object { $_ -match 'LEAK: ByteBuf' }).Count
    }
}

$files = New-Object System.Collections.ArrayList
# 优先用仓库自带 Wrapper（与 CI 同一 Maven 版本）
$mvnCmd = Join-Path $PSScriptRoot '..\mvnw.cmd'
if (-not (Test-Path $mvnCmd)) { $mvnCmd = 'mvn' }
$mvnFailures = 0
$leakFailures = 0

if ($Run) {
    if (-not (Test-Path -LiteralPath $LogDir -PathType Container)) {
        New-Item -ItemType Directory -Path $LogDir | Out-Null
    }
    $argLine = '-Dio.netty.leakDetection.level=paranoid -Dio.netty.leakDetection.targetRecords=16 -XshowSettings:properties'
    for ($r = 1; $r -le $Rounds; $r++) {
        $log = Join-Path $LogDir ("netty-leak-check-round{0}.log" -f $r)
        Write-Output ("==> [round {0}/{1}] paranoid full test run -> {2}" -f $r, $Rounds, $log)
        $mvnArgs = @('-o')
        if ($Clean) { $mvnArgs += 'clean' }
        $mvnArgs += @('test', '-Djacoco.skip=true', ("-DargLine=" + $argLine))
        if ($ExtraMvnArgs.Trim().Length -gt 0) {
            $mvnArgs += $ExtraMvnArgs.Trim().Split(' ')
        }
        & $mvnCmd @mvnArgs 2>&1 | Tee-Object -FilePath $log | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Output ("    [FAIL] maven exit code {0}: build or test failure (NOT necessarily a leak; see {1})" -f $LASTEXITCODE, $log)
            $mvnFailures++
        }
        $dump = @(Get-LogLines -File $log |
            Where-Object { $_ -match 'io\.netty\.leakDetection\.level = paranoid' }).Count
        if ($dump -eq 0) {
            Write-Output ("    [WARN] paranoid property dump not found in {0}: cannot prove the level took effect" -f $log)
        }
        [void]$files.Add($log)
    }
} else {
    foreach ($raw in ($Paths -split ';')) {
        $target = $raw.Trim().Trim('"')
        if ([string]::IsNullOrWhiteSpace($target)) { continue }
        if (Test-Path -LiteralPath $target -PathType Container) {
            foreach ($f in (Get-ChildItem -LiteralPath $target -Filter '*.log' -File | Sort-Object Name)) {
                [void]$files.Add($f.FullName)
            }
        } elseif (Test-Path -LiteralPath $target) {
            [void]$files.Add((Resolve-Path -LiteralPath $target).Path)
        } else {
            Write-Output ("skip (not found): {0}" -f $target)
        }
    }
}

if ($files.Count -eq 0) {
    Write-Output "ERROR: no log file to check"
    exit 2
}

Write-Output ""
Write-Output "=== leak report tally (markers: reportTracedLeak | reportUntracedLeak | LEAK: ByteBuf) ==="
foreach ($f in $files) {
    $c = Get-LeakCounts -File $f
    $name = [System.IO.Path]::GetFileName($f)
    $total = $c.Traced + $c.Untraced + $c.Slf4j
    if ($total -gt 0) {
        Write-Output ("{0,-52} traced={1,-4} untraced={2,-4} slf4j={3,-4} <= LEAK REPORTS FOUND" -f $name, $c.Traced, $c.Untraced, $c.Slf4j)
        $leakFailures++
    } else {
        Write-Output ("{0,-52} traced=0    untraced=0    slf4j=0    no leak report OK" -f $name)
    }
}

Write-Output ""
if ($mvnFailures -gt 0) {
    Write-Output ("[FAIL] maven exit code != 0 in {0} round(s): build or test failure (see the round log)" -f $mvnFailures)
}
if ($leakFailures -gt 0) {
    Write-Output ("[FAIL] Netty ByteBuf leak reports present in {0} log(s) (see 'Created at:' for the allocation site)" -f $leakFailures)
    Write-Output "       look at: NettyMultipartWebRequest.release() / refused response write path / pipelining refcount balance"
}
if ($mvnFailures -gt 0 -or $leakFailures -gt 0) {
    exit 1
}
Write-Output "[OK] no Netty ByteBuf leak report"
exit 0

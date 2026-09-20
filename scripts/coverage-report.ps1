#requires -Version 5.1
<#
.SYNOPSIS
    一键生成所有模块的单元测试覆盖率报告（根目录 coverage-report.md，可覆盖，带生成时间，中英双语）。
    Generate a bilingual (zh/en) unit-test coverage report at the repo root.

.DESCRIPTION
    1) 默认先执行 `mvn test jacoco:report` 刷新覆盖率数据（可加 -SkipTests 跳过，仅汇总已有报告）；
    2) 解析各模块 target/site/jacoco/jacoco.csv，汇总行/分支/指令/方法覆盖率、达标状态、测试规模与未覆盖热点；
    3) 在仓库根目录生成 coverage-report.md（覆盖旧文件），头部带生成时间。

.PARAMETER SkipTests
    跳过 maven 测试与报告生成，仅基于现有 jacoco.csv 汇总（需先手动运行过 mvn test）。

.PARAMETER OutFile
    输出报告路径，默认 <仓库根>/coverage-report.md。

.EXAMPLE
    .\coverage-report.ps1
    .\coverage-report.ps1 -SkipTests
#>
param(
    [switch]$SkipTests,
    [string]$OutFile = ""
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# 库模块（纳入覆盖率统计）/ library modules (coverage scope)
$modules = @(
    'spring-web',
    'spring-web-view',
    'spring-web-servlet',
    'spring-web-mvc-support',
    'spring-web-batch',
    'spring-web-websocket',
    'spring-boot-starter-web'
)
# E2E 模块 / E2E modules
$e2eModules = @('spring-web-test', 'spring-web-support-test')

# 脚本位于 scripts/，仓库根为其父目录
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $OutFile) {
    $OutFile = Join-Path $repoRoot 'coverage-report.md'
}

# 刷新范围：库模块 + E2E 模块 + 聚合模块（聚合模块排最后，report-aggregate 合并全部 exec，
# 这样 E2E 触发的库模块覆盖才会计入报告）。
# Refresh scope: library modules + E2E modules + the aggregate module (kept last; report-aggregate
# merges every exec file, which is what brings E2E-driven library coverage into the report).
$refreshModules = $modules + $e2eModules + @('coverage-aggregate')

# 合并后的 CSV（GROUP 列形如 `coverage-aggregate/spring-web`，取末段即模块名）。
# The merged CSV produced by report-aggregate; its GROUP column looks like
# `coverage-aggregate/spring-web`, so the last path segment is the module name.
$aggregateCsv = Join-Path $repoRoot 'coverage-aggregate/target/site/jacoco-aggregate/jacoco.csv'

# ---------- 1. 刷新覆盖率数据 / refresh coverage data ----------
if (-not $SkipTests) {
    Write-Host "[coverage-report] 运行测试并生成聚合覆盖率数据 / running tests & generating aggregate coverage data ..." -ForegroundColor Cyan
    $mvnArgs = @('test', '-pl', ($refreshModules -join ','))
    Push-Location $repoRoot
    try {
        & mvn @mvnArgs
        if ($LASTEXITCODE -ne 0) {
            throw "mvn test jacoco:report 失败，exit=$LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
} else {
    Write-Host "[coverage-report] -SkipTests：仅汇总已有 jacoco.csv / aggregating existing jacoco.csv only" -ForegroundColor Cyan
}

# ---------- 2. 汇总各模块覆盖率 / aggregate per-module coverage ----------
# 口径：读取 report-aggregate 产出的**合并 CSV**（coverage-aggregate 模块），
# 因此「E2E 触发的库模块覆盖」也计入——与聚合 HTML 报告同一数据源。
# Scope: read the merged CSV produced by report-aggregate (coverage-aggregate module), so
# E2E-driven coverage of library modules is included — same data as the aggregate HTML.
$aggregateRows = @()

function Load-AggregateRows {
    if (-not (Test-Path $aggregateCsv)) {
        throw ("缺少合并 CSV：{0}；请先运行 mvn test -pl {1}（或去掉 -SkipTests）。" -f $aggregateCsv, ($refreshModules -join ','))
    }
    return @(Import-Csv $aggregateCsv)
}

function Get-ModuleCoverage([string]$module) {
    # GROUP 形如 `coverage-aggregate/<module>`，取末段比对（E2E 模块自身类被 excludes 排除，不会出现）
    $rows = @($aggregateRows | Where-Object { ($_.GROUP -split '/')[-1] -eq $module })
    if ($rows.Count -eq 0) {
        return $null
    }
    $lineMissed = 0; $lineCovered = 0
    $brMissed = 0; $brCovered = 0
    $instrMissed = 0; $instrCovered = 0
    $methodMissed = 0; $methodCovered = 0
    foreach ($r in $rows) {
        $lineMissed += [int]$r.LINE_MISSED; $lineCovered += [int]$r.LINE_COVERED
        $brMissed += [int]$r.BRANCH_MISSED; $brCovered += [int]$r.BRANCH_COVERED
        $instrMissed += [int]$r.INSTRUCTION_MISSED; $instrCovered += [int]$r.INSTRUCTION_COVERED
        $methodMissed += [int]$r.METHOD_MISSED; $methodCovered += [int]$r.METHOD_COVERED
    }
    $lineTotal = $lineMissed + $lineCovered
    $brTotal = $brMissed + $brCovered
    $instrTotal = $instrMissed + $instrCovered
    $methodTotal = $methodMissed + $methodCovered
    return [pscustomobject]@{
        Module          = $module
        Classes         = $rows.Count
        Lines           = if ($lineTotal -gt 0) { [math]::Round(100.0 * $lineCovered / $lineTotal, 1) } else { 0 }
        LineCovered     = $lineCovered
        LineTotal       = $lineTotal
        Branches        = if ($brTotal -gt 0) { [math]::Round(100.0 * $brCovered / $brTotal, 1) } else { 0 }
        BrCovered       = $brCovered
        BrTotal         = $brTotal
        Instr           = if ($instrTotal -gt 0) { [math]::Round(100.0 * $instrCovered / $instrTotal, 1) } else { 0 }
        InstrCovered    = $instrCovered
        InstrTotal      = $instrTotal
        Methods         = if ($methodTotal -gt 0) { [math]::Round(100.0 * $methodCovered / $methodTotal, 1) } else { 0 }
        MethodCovered   = $methodCovered
        MethodTotal     = $methodTotal
        Hotspots        = @($rows | Sort-Object -Property { [int]$_.LINE_MISSED } -Descending |
            Select-Object -First 5 | ForEach-Object {
                $tot = [int]$_.LINE_MISSED + [int]$_.LINE_COVERED
                [pscustomobject]@{
                    Class   = $_.CLASS
                    Missed  = [int]$_.LINE_MISSED
                    Total   = $tot
                    Percent = if ($tot -gt 0) { [math]::Round(100.0 * [int]$_.LINE_COVERED / $tot, 1) } else { 0 }
                }
            })
    }
}

$aggregateRows = Load-AggregateRows
$results = @()
$totalLineCovered = 0; $totalLineAll = 0
$totalBrCovered = 0; $totalBrAll = 0
$totalInstrCovered = 0; $totalInstrAll = 0
$totalMethodCovered = 0; $totalMethodAll = 0

foreach ($m in $modules) {
    $r = Get-ModuleCoverage $m
    if ($r -eq $null) {
        Write-Warning "合并 CSV 中缺少模块 $m（未运行测试？）/ module $m missing in merged CSV"
        continue
    }
    $results += $r
    $totalLineCovered += $r.LineCovered;   $totalLineAll += $r.LineTotal
    $totalBrCovered += $r.BrCovered;       $totalBrAll += $r.BrTotal
    $totalInstrCovered += $r.InstrCovered; $totalInstrAll += $r.InstrTotal
    $totalMethodCovered += $r.MethodCovered; $totalMethodAll += $r.MethodTotal
}

if ($results.Count -eq 0) {
    throw '未找到任何模块的 jacoco.csv，请先运行 mvn test 或去掉 -SkipTests。/ No jacoco.csv found.'
}

$overallLine = if ($totalLineAll -gt 0) { [math]::Round(100.0 * $totalLineCovered / $totalLineAll, 1) } else { 0 }
$overallBr = if ($totalBrAll -gt 0) { [math]::Round(100.0 * $totalBrCovered / $totalBrAll, 1) } else { 0 }
$overallInstr = if ($totalInstrAll -gt 0) { [math]::Round(100.0 * $totalInstrCovered / $totalInstrAll, 1) } else { 0 }
$overallMethod = if ($totalMethodAll -gt 0) { [math]::Round(100.0 * $totalMethodCovered / $totalMethodAll, 1) } else { 0 }

# ---------- 3. 测试规模 / test counts (surefire) ----------
function Get-TestCount([string]$module) {
    $dir = Join-Path $repoRoot (Join-Path $module 'target/surefire-reports')
    if (-not (Test-Path $dir)) {
        return 0
    }
    $total = 0
    Get-ChildItem (Join-Path $dir '*.txt') -ErrorAction SilentlyContinue | ForEach-Object {
        $m = Select-String -Path $_.FullName -Pattern 'Tests run:\s*(\d+)' | Select-Object -First 1
        if ($m -and $m.Matches.Count -gt 0) {
            $total += [int]$m.Matches[0].Groups[1].Value
        }
    }
    return $total
}

$unitTotal = 0
$testRows = @()
foreach ($m in $modules) {
    $n = Get-TestCount $m
    $testRows += [pscustomobject]@{ Module = $m; Count = $n }
    $unitTotal += $n
}
$e2eTotal = 0
$e2eRows = @()
foreach ($m in $e2eModules) {
    $n = Get-TestCount $m
    $e2eRows += [pscustomobject]@{ Module = $m; Count = $n }
    $e2eTotal += $n
}

# ---------- 4. 生成报告 / generate report (overwrite) ----------
$generatedAt = Get-Date
$platform = $env:OS
if (-not $platform) { $platform = 'POSIX' }
$javaVer = 'java: n/a'
try {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $javaVer = (java -version 2>&1 | Select-Object -First 1)
    $ErrorActionPreference = $prev
} catch {
    $javaVer = 'java: n/a'
}

$sb = New-Object System.Text.StringBuilder
function W([string]$line) { [void]$sb.AppendLine($line) }

W "# 单元测试覆盖率报告 / Unit Test Coverage Report"
W ""
W "- **生成时间 / Generated at**：$($generatedAt.ToString('yyyy-MM-dd HH:mm:ss'))"
W "- **环境 / Environment**：$platform / $javaVer"
W "- **覆盖范围 / Scope**：库模块，**单测 + E2E 合并口径**（JaCoCo，coverage-aggregate 模块 report-aggregate 合并后的 jacoco.csv）"
W "  Library modules, unit tests **plus E2E** (JaCoCo, merged jacoco.csv from the coverage-aggregate module)"
W "- **覆盖目标 / Targets**：spring-web ≥90%，其余库模块 ≥80%（✅=达标 ✓，❌=未达标 ✗）"
W "  spring-web ≥90%, other library modules ≥80% (✅=met, ❌=missed)"
W "- **汇总 / Summary**：行/Line $overallLine% · 分支/Branch $overallBr% · 指令/Instr $overallInstr% · 方法/Method $overallMethod%"
W ""

W "## 一、覆盖率总览 / 1. Coverage Overview"
W ""
W "| 模块 / Module | 行 / Line | 行覆盖 / Lines | 分支 / Branch | 分支覆盖 / Branches | 指令 / Instr | 方法 / Method | 类数 / Classes | 目标 / Target | 状态 / Status |"
W "|---|---|---|---|---|---|---|---|---|---|"
foreach ($r in $results) {
    $target = if ($r.Module -eq 'spring-web') { 90 } else { 80 }
    $ok = if ($r.Lines -ge $target) { '✅' } else { '❌' }
    W "| $($r.Module) | $($r.Lines)% | $($r.LineCovered)/$($r.LineTotal) | $($r.Branches)% | $($r.BrCovered)/$($r.BrTotal) | $($r.Instr)% | $($r.Methods)% | $($r.Classes) | ≥$target% | $ok |"
}
W "| **汇总 / Total** | **$overallLine%** | **$totalLineCovered/$totalLineAll** | **$overallBr%** | **$totalBrCovered/$totalBrAll** | **$overallInstr%** | **$overallMethod%** | | | |"
W ""

W "## 二、测试规模 / 2. Test Count"
W ""
W "| 模块 / Module | 用例数 / Tests |"
W "|---|---|"
foreach ($r in $testRows) { W "| $($r.Module) | $($r.Count) |" }
W "| **库模块合计 / Library total** | **$unitTotal** |"
foreach ($r in $e2eRows) { W "| E2E $($r.Module) | $($r.Count) |" }
W "| **E2E 合计 / E2E total** | **$e2eTotal** |"
W ""
W "> 说明 / Note：本脚本会运行库模块 + E2E 模块；E2E 触发的库模块覆盖由聚合模块合并后计入第一节。"
W "> Both library and E2E modules are run; E2E-driven library coverage is merged in by the aggregate module."
W ""

W "## 三、未覆盖热点（各模块未覆盖行最多的类 Top 5）/ 3. Coverage Hotspots (Top 5 classes by missed lines per module)"
W ""
foreach ($r in $results) {
    W "### $($r.Module)"
    W ""
    W "| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |"
    W "|---|---|---|---|"
    foreach ($h in $r.Hotspots) {
        W "| $($h.Class) | $($h.Missed) | $($h.Total) | $($h.Percent)% |"
    }
    W ""
}

# 以**无 BOM** 的 UTF-8 写出：仓库的 check-docs 规则禁止 BOM，而 PS 5.1 的
# `Set-Content -Encoding utf8` 总会写入 BOM（并因此让首行 `# ...` 不被识别为 H1，
# 触发 h1-count 缺陷）。这里用 .NET API 显式指定不带 BOM 的编码。
# Write UTF-8 **without BOM**: the repo's check-docs rule forbids a BOM, but PS 5.1's
# `Set-Content -Encoding utf8` always emits one (which also hides the leading `# ...`
# from H1 detection). Use the .NET API with an explicit no-BOM encoding instead.
[System.IO.File]::WriteAllText($OutFile, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))

Write-Host ''
Write-Host "[coverage-report] 报告已生成 / report generated: $OutFile" -ForegroundColor Green
Write-Host ("[coverage-report] 汇总 / summary: line {0}% / branch {1}%（{2}）" -f $overallLine, $overallBr, $generatedAt.ToString('yyyy-MM-dd HH:mm:ss'))

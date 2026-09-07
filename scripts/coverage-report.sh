#!/usr/bin/env bash
#
# coverage-report.sh
# 一键生成所有模块单元测试覆盖率报告（根目录 coverage-report.md，可覆盖，带生成时间，中英双语）。
# Generate a bilingual (zh/en) unit-test coverage report at the repo root.
# 跨平台：Windows（Git Bash / WSL / MSYS2）、Linux、macOS。
#
# 用法 / Usage:
#   ./coverage-report.sh            # 运行 mvn test jacoco:report 后汇总生成报告
#   ./coverage-report.sh -s|--skip  # 跳过 mvn，仅基于已有 target/site/jacoco/jacoco.csv 汇总
#
set -euo pipefail

MODULES=(
  spring-web
  spring-web-view
  spring-web-servlet
  spring-web-mvc-support
  spring-web-batch
  spring-web-websocket
  spring-boot-starter-web
)
E2E_MODULES=(spring-web-test spring-web-support-test)

SKIP_MVN=false
if [[ "${1:-}" == "--skip" || "${1:-}" == "-s" ]]; then
  SKIP_MVN=true
fi

# 脚本位于 scripts/，仓库根为其父目录
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_FILE="$REPO_ROOT/coverage-report.md"
GENERATED_AT="$(date '+%Y-%m-%d %H:%M:%S')"
PLATFORM="$(uname -s 2>/dev/null || echo 'Windows')"
JAVA_VERSION="$(java -version 2>&1 | head -n1 || echo 'java: n/a')"

# ---------- 1. 刷新覆盖率数据 / refresh coverage data ----------
if [[ "$SKIP_MVN" == "false" ]]; then
  echo "[coverage-report] 运行测试并生成 JaCoCo 报告 / running tests & generating JaCoCo report ..."
  (cd "$REPO_ROOT" && mvn test jacoco:report -pl "$(IFS=,; echo "${MODULES[*]}")")
else
  echo "[coverage-report] --skip：仅汇总已有 jacoco.csv / aggregating existing jacoco.csv only"
fi

# ---------- 2. 汇总各模块覆盖率 / aggregate per-module coverage (awk parse jacoco.csv) ----------
# 列序/cols: GROUP,PACKAGE,CLASS,INSTRUCTION_M,C,BRANCH_M,C,LINE_M,C,COMPLEXITY_M,C,METHOD_M,C
declare -a ROWS=()
declare -a HOTSPOTS=()
TOTAL_IC=0; TOTAL_IA=0; TOTAL_LC=0; TOTAL_LA=0
TOTAL_BC=0; TOTAL_BA=0; TOTAL_MC=0; TOTAL_MA=0

for m in "${MODULES[@]}"; do
  csv="$REPO_ROOT/$m/target/site/jacoco/jacoco.csv"
  if [[ ! -f "$csv" ]]; then
    echo "[coverage-report] 警告：缺少 $csv（未运行测试？）/ warning: missing $csv" >&2
    continue
  fi

  read -r IC IA LC LA BC BA MC MA CLASSES <<< "$(
    awk -F, 'NR>1{im+=$4;ic+=$5;bm+=$6;bc+=$7;lm+=$8;lc+=$9;mm+=$12;mc+=$13;c++}
             END{printf "%d %d %d %d %d %d %d %d %d",ic,ic+im,lc,lc+lm,bc,bc+bm,mc,mc+mm,c}' "$csv"
  )"

  line_pct=$(awk "BEGIN{printf \"%.1f\",100.0*$LC/$LA}")
  br_pct=$(awk "BEGIN{printf \"%.1f\",100.0*$BC/$BA}")
  instr_pct=$(awk "BEGIN{printf \"%.1f\",100.0*$IC/$IA}")
  meth_pct=$(awk "BEGIN{printf \"%.1f\",100.0*$MC/$MA}")

  target=80
  [[ "$m" == "spring-web" ]] && target=90
  status=''
  if awk "BEGIN{exit !($LC/$LA*100 >= $target)}"; then status='✅'; else status='❌'; fi

  ROWS+=("$m|$line_pct|$LC/$LA|$br_pct|$BC/$BA|$instr_pct|$meth_pct|$CLASSES|$target|$status")

  TOTAL_IC=$((TOTAL_IC+IC)); TOTAL_IA=$((TOTAL_IA+IA))
  TOTAL_LC=$((TOTAL_LC+LC)); TOTAL_LA=$((TOTAL_LA+LA))
  TOTAL_BC=$((TOTAL_BC+BC)); TOTAL_BA=$((TOTAL_BA+BA))
  TOTAL_MC=$((TOTAL_MC+MC)); TOTAL_MA=$((TOTAL_MA+MA))

  # 未覆盖热点 / uncovered hotspots: Top 5 classes by missed lines
  HOTSPOTS+=("=== $m ===")
  while IFS= read -r line; do
    HOTSPOTS+=("$line")
  done < <(awk -F, 'NR>1{printf "%s|%s|%d|%.1f\n",$3,$8,$8+$9,100.0*$9/($8+$9)}' "$csv" \
            | sort -t'|' -k2 -rn | head -5)
done

if [[ ${#ROWS[@]} -eq 0 ]]; then
  echo "[coverage-report] 错误：未找到任何模块的 jacoco.csv / error: no jacoco.csv found" >&2
  exit 1
fi

overall_line=$(awk "BEGIN{printf \"%.1f\",100.0*$TOTAL_LC/$TOTAL_LA}")
overall_br=$(awk "BEGIN{printf \"%.1f\",100.0*$TOTAL_BC/$TOTAL_BA}")
overall_instr=$(awk "BEGIN{printf \"%.1f\",100.0*$TOTAL_IC/$TOTAL_IA}")
overall_meth=$(awk "BEGIN{printf \"%.1f\",100.0*$TOTAL_MC/$TOTAL_MA}")

# ---------- 3. 测试规模 / test counts (surefire reports) ----------
count_tests() {
  local dir="$REPO_ROOT/$1/target/surefire-reports"
  [[ -d "$dir" ]] || { echo 0; return; }
  echo "$(grep -h 'Tests run:' "$dir"/*.txt 2>/dev/null | awk -F'[ ,]+' '{s+=$3} END{print s+0}')"
}
declare -a TEST_ROWS=()
UNIT_TOTAL=0
for m in "${MODULES[@]}"; do
  n=$(count_tests "$m"); TEST_ROWS+=("$m|$n"); UNIT_TOTAL=$((UNIT_TOTAL+n))
done
declare -a E2E_ROWS=()
E2E_TOTAL=0
for m in "${E2E_MODULES[@]}"; do
  n=$(count_tests "$m"); E2E_ROWS+=("$m|$n"); E2E_TOTAL=$((E2E_TOTAL+n))
done

# ---------- 4. 生成报告 / generate report (overwrite) ----------
{
  echo "# 单元测试覆盖率报告 / Unit Test Coverage Report"
  echo ""
  echo "- **生成时间 / Generated at**：$GENERATED_AT"
  echo "- **环境 / Environment**：$PLATFORM / $JAVA_VERSION"
  echo "- **覆盖范围 / Scope**：库模块单元测试（JaCoCo，基于 target/site/jacoco/jacoco.csv）"
  echo "  Library module unit tests (JaCoCo, based on target/site/jacoco/jacoco.csv)"
  echo "- **覆盖目标 / Targets**：spring-web ≥90%，其余库模块 ≥80%（✅=达标 ✓，❌=未达标 ✗）"
  echo "  spring-web ≥90%, other library modules ≥80% (✅=met, ❌=missed)"
  echo "- **汇总 / Summary**：行/Line $overall_line% · 分支/Branch $overall_br% · 指令/Instr $overall_instr% · 方法/Method $overall_meth%"
  echo ""

  echo "## 一、覆盖率总览 / 1. Coverage Overview"
  echo ""
  echo "| 模块 / Module | 行 / Line | 行覆盖 / Lines | 分支 / Branch | 分支覆盖 / Branches | 指令 / Instr | 方法 / Method | 类数 / Classes | 目标 / Target | 状态 / Status |"
  echo "|---|---|---|---|---|---|---|---|---|---|"
  for row in "${ROWS[@]}"; do
    IFS='|' read -r m lp lc bp bc ip mp cl tgt st <<< "$row"
    echo "| $m | $lp% | $lc | $bp% | $bc | $ip% | $mp% | $cl | ≥$tgt% | $st |"
  done
  echo "| **汇总 / Total** | **$overall_line%** | **$TOTAL_LC/$TOTAL_LA** | **$overall_br%** | **$TOTAL_BC/$TOTAL_BA** | **$overall_instr%** | **$overall_meth%** | | | |"
  echo ""

  echo "## 二、测试规模 / 2. Test Count"
  echo ""
  echo "| 模块 / Module | 用例数 / Tests |"
  echo "|---|---|"
  for row in "${TEST_ROWS[@]}"; do
    IFS='|' read -r m n <<< "$row"
    echo "| $m | $n |"
  done
  echo "| **库模块合计 / Library total** | **$UNIT_TOTAL** |"
  for row in "${E2E_ROWS[@]}"; do
    IFS='|' read -r m n <<< "$row"
    echo "| E2E $m | $n |"
  done
  echo "| **E2E 合计 / E2E total** | **$E2E_TOTAL** |"
  echo ""
  echo "> 说明 / Note：E2E 用例默认不随本脚本执行，上表基于已存在的 surefire 报告；如需刷新请单独运行"
  echo "> E2E tests are not run by this script; the table above reflects existing surefire reports."
  echo "> 刷新命令 / To refresh: mvn test -pl spring-web-test,spring-web-support-test"
  echo ""

  echo "## 三、未覆盖热点（各模块未覆盖行最多的类 Top 5）/ 3. Coverage Hotspots (Top 5 classes by missed lines per module)"
  echo ""
  current=''
  for line in "${HOTSPOTS[@]}"; do
    if [[ "$line" == ===* ]]; then
      current="${line#=== }"
      current="${current% ===}"
      echo "### $current"
      echo ""
      echo "| 类 / Class | 未覆盖行 / Missed | 总行 / Total | 覆盖率 / Coverage |"
      echo "|---|---|---|---|"
      continue
    fi
    IFS='|' read -r cls missed total pct <<< "$line"
    echo "| $cls | $missed | $total | $pct% |"
  done
  echo ""
} > "$OUT_FILE"

echo ""
echo "[coverage-report] 报告已生成 / report generated: $OUT_FILE"
echo "[coverage-report] 汇总 / summary: line $overall_line% / branch $overall_br%（$GENERATED_AT）"

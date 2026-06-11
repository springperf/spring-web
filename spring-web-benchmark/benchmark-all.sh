#!/bin/bash
#
# benchmark-all.sh — Spring Web Benchmark 一键全量运行脚本 (Linux / macOS / WSL)
#
# 直接绕过 jmh-maven-plugin，使用 java -cp 运行 BenchmarkRunner。
# 每个 profile: clean compile → classpath → run (fork=1) 在单次循环中完成。
# 用法:
#   ./benchmark-all.sh                           # 默认: 全部 10 profile
#   ./benchmark-all.sh --profiles perf,tomcat    # 只跑指定 profile
#
# 输出: benchmark-reports/{run-id}/report.md

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

RUN_ID=$(date +"%Y%m%d-%H%M%S")
REPORTS_DIR="benchmark-reports"

# 检测 OS 类型，选择正确的 classpath 分隔符
# Windows (Git Bash/MSYS/CYGWIN): mvn dependency:build-classpath 输出 ; 分隔
# Linux/macOS: mvn dependency:build-classpath 输出 : 分隔
if uname | grep -iq "mingw\|cygwin\|msys"; then
  CP_SEP=";"
else
  CP_SEP=":"
fi

# 默认 profiles 列表: "profile_name:port:benchmark_class"
DEFAULT_PROFILES=(
  "perf:9091:PerfBenchmark"
  "perf-filter:9092:PerfFilterBenchmark"
  "perf-support:9093:PerfSupportBenchmark"
  "perf-support-filter:9094:PerfSupportFilterBenchmark"
  "tomcat:9101:TomcatBenchmark"
  "tomcat-filter:9102:TomcatFilterBenchmark"
  "undertow:9111:UndertowBenchmark"
  "undertow-filter:9112:UndertowFilterBenchmark"
  "webflux:9121:WebFluxBenchmark"
  "webflux-filter:9122:WebFluxFilterBenchmark"
)

# 解析参数
PROFILES_LIST=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --profiles)
      IFS=',' read -ra PROFILES_LIST <<< "$2"
      shift 2
      ;;
    *)
      echo "Usage: $0 [--profiles perf,tomcat,...]"
      exit 1
      ;;
  esac
done

echo "=========================================="
echo " Spring Web Benchmark — $RUN_ID"
echo "=========================================="

# ========== 检测 JDK 版本 ==========
JDK_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2)
JDK_DIR_NAME="jdk-${JDK_VERSION// /_}"
RESULTS_DIR="$REPORTS_DIR/$RUN_ID/$JDK_DIR_NAME"

# 判断 GC 日志格式
if echo "$JDK_VERSION" | grep -q "^1\.8"; then
  echo "GC log format: JDK 8 (PrintGCDetails)"
  GC_MODE="jdk8"
else
  echo "GC log format: JDK 11+ (Xlog)"
  GC_MODE="jdk11"
fi

mkdir -p "$RESULTS_DIR"

# ========== Step 1: 从根目录全量编译（安装依赖到本地仓库） ==========
echo ""
echo "[1/4] 全量编译所有模块..."
cd "$SCRIPT_DIR/.."
mvn clean install -DskipTests -q
if [ $? -ne 0 ]; then
  echo "[ERROR] 编译失败"
  exit 1
fi
cd "$SCRIPT_DIR"

# 确定要执行的 profiles
PROFILES_TO_RUN=()
if [ ${#PROFILES_LIST[@]} -gt 0 ]; then
  for P in "${PROFILES_LIST[@]}"; do
    for ENTRY in "${DEFAULT_PROFILES[@]}"; do
      PROFILE_NAME="${ENTRY%%:*}"
      if [ "$PROFILE_NAME" = "$P" ]; then
        PROFILES_TO_RUN+=("$ENTRY")
      fi
    done
  done
else
  PROFILES_TO_RUN=("${DEFAULT_PROFILES[@]}")
fi

# ========== Step 2+3: 逐个 profile 编译 → 构建 classpath → 运行 ==========
echo ""
echo "[2/4] 逐个 profile 编译、构建 classpath 并运行..."
TOTAL=${#PROFILES_TO_RUN[@]}
COUNT=0

for ENTRY in "${PROFILES_TO_RUN[@]}"; do
  IFS=':' read -r PROFILE PORT BENCH_CLASS <<< "$ENTRY"
  COUNT=$((COUNT + 1))

  echo "  [$COUNT/$TOTAL] $PROFILE (port $PORT)"

  # 编译（clean compile 确保 JMH 桩代码正确生成）
  echo "    [compile] benchmark-$PROFILE..."
  mvn -P"benchmark-$PROFILE" clean compile -q
  if [ $? -ne 0 ]; then
    echo "    -> COMPILE FAIL"
    exit 1
  fi

  # 构建 classpath
  echo "    [classpath] benchmark-$PROFILE..."
  mvn -P"benchmark-$PROFILE" dependency:build-classpath -Dmdep.outputFile="target/cp-$PROFILE.txt" -q

  # GC 日志参数
  GC_LOG_PATH="$(pwd)/$RESULTS_DIR/gc-$PROFILE.log"
  if [ "$GC_MODE" = "jdk8" ]; then
    GC_ARG="-XX:+PrintGCDetails -Xloggc:${GC_LOG_PATH} -XX:+PrintGCDateStamps"
  else
    GC_ARG="-Xlog:gc*=info:file=${GC_LOG_PATH}:time,uptime,level,tags"
  fi

  # 读取 classpath
  CP=$(head -1 "target/cp-$PROFILE.txt" 2>/dev/null || echo "")

  # 运行基准测试（fork=1 隔离 JVM）
  echo "    [benchmark] $PROFILE..."
  java -cp "target/classes${CP_SEP}${CP}" \
    -Djmh.forks=1 \
    -Dbenchmark.port="$PORT" \
    -Dbenchmark.profile.name="$PROFILE" \
    -Dbenchmark.output.dir="$(pwd)/$RESULTS_DIR" \
    -Dbenchmark.gc.log.arg="$GC_ARG" \
    -Dbenchmark.include=".*${BENCH_CLASS}.*" \
    io.springperf.benchmark.BenchmarkRunner

  if [ $? -eq 0 ]; then
    echo "    -> SUCCESS"
  else
    echo "    -> FAIL"
  fi
done

# ========== Step 4: 生成报告 ==========
echo ""
echo "[4/4] 生成报告..."
rm -f target/cp-report.txt
mvn dependency:build-classpath -Dmdep.outputFile="target/cp-report.txt" -q 2>/dev/null
if [ -f "target/cp-report.txt" ]; then
  CP_REPORT=$(head -1 "target/cp-report.txt")
  java -cp "target/classes${CP_SEP}${CP_REPORT}" \
    io.springperf.benchmark.report.generator.ReportGenerator "$(pwd)/$REPORTS_DIR"
else
  java -cp "target/classes" \
    io.springperf.benchmark.report.generator.ReportGenerator "$(pwd)/$REPORTS_DIR" 2>/dev/null || \
  echo "[WARN] ReportGenerator 无法直接运行，请确保 classpath 完整"
fi

echo ""
echo "=========================================="
echo " 完成!"
echo " 报告: $(pwd)/$REPORTS_DIR/$RUN_ID/report.md"
echo "=========================================="
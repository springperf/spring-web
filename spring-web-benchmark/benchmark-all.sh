#!/bin/bash
#
# benchmark-all.sh — Spring Web Benchmark 一键全量运行脚本 (Linux / macOS / WSL)
#
# 直接绕过 jmh-maven-plugin，使用 java -cp 运行 BenchmarkRunner。
# 每个 profile 编译并运行一次服务器实例，所有 API 在同一 JVM 中顺序执行。
# 用法:
#   ./benchmark-all.sh                                    # 默认: 全部 5 profile × 8 接口
#   ./benchmark-all.sh --profiles perf,perf-support,tomcat,undertow             # 只跑指定 profile
#   ./benchmark-all.sh --api json                # 只跑指定接口
#   ./benchmark-all.sh --apis json,bytes         # 跑指定多个接口
#   ./benchmark-all.sh --jdk /path/to/jdk17     # 用指定 JDK 运行（JDK8 编译，多 JDK 跑）
#   ./benchmark-all.sh --jdk java,/path/to/jdk17  # 同时跑默认 JDK 和指定 JDK
#   ./benchmark-all.sh --thread-list 1,4,16,64  # 多线程并发度测试
# 输出: benchmark-reports/{run-id}/report.md

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

RUN_ID=$(date +"%Y%m%d-%H%M%S")
REPORTS_DIR="benchmark-reports"
EXTRA_JVM_ARGS=""

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
  "perf:9092:PerfBenchmark"
  "perf-support:9094:PerfSupportBenchmark"
  "tomcat:9102:TomcatBenchmark"
  "undertow:9112:UndertowBenchmark"
  "webflux:9122:WebFluxBenchmark"
)

# 默认 API 列表（与 AbstractServerBenchmark 中的 @Benchmark 方法名一致）
DEFAULT_APIS=(
  "json"
  "get"
  "async"
  "bytes"
  "valid"
  "bytesLarge"
  "sse"
)

# 解析参数
PROFILES_LIST=()
APIS_LIST=()
JDK_LIST=()
THREAD_LIST=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --profiles)
      IFS=',' read -ra PROFILES_LIST <<< "$2"
      shift 2
      ;;
    --api)
      APIS_LIST=("$2")
      shift 2
      ;;
    --apis)
      IFS=',' read -ra APIS_LIST <<< "$2"
      shift 2
      ;;
    --jdk|--jdks)
      IFS=',' read -ra JDK_LIST <<< "$2"
      shift 2
      ;;
    --thread-list)
      IFS=',' read -ra THREAD_LIST <<< "$2"
      shift 2
      ;;
    --sampleTime)
	      EXTRA_JVM_ARGS="$EXTRA_JVM_ARGS -Dbenchmark.sampleTime=true"
	      shift
	      ;;
	    --threads)
      EXTRA_JVM_ARGS="$EXTRA_JVM_ARGS -Dbenchmark.threads=$2"
      shift 2
      ;;
    *)
      echo "Usage: $0 [--profiles perf,tomcat,...] [--api name|--apis a,b,c] [--jdk /path/to/jdk11,/path/to/jdk17] [--thread-list 1,4,16,64] [--sampleTime]"
      exit 1
      ;;
  esac
done

echo "=========================================="
echo " Spring Web Benchmark — $RUN_ID"
echo "=========================================="

# ========== 确定执行参数 ==========
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

# 确定要执行的 APIs（仅用于过滤）
if [ ${#APIS_LIST[@]} -gt 0 ]; then
  APIS_TO_RUN=("${APIS_LIST[@]}")
else
  APIS_TO_RUN=("${DEFAULT_APIS[@]}")
fi

# 确定要执行的 JDK 列表
if [ ${#JDK_LIST[@]} -eq 0 ]; then
  JDK_LIST=("java")
fi

# 确定要执行的线程数列表
THREADS_TO_RUN=()
USE_THREAD_SUBDIRS=false
if [ ${#THREAD_LIST[@]} -gt 0 ]; then
  THREADS_TO_RUN=("${THREAD_LIST[@]}")
  USE_THREAD_SUBDIRS=true
  echo "  Thread counts: ${THREAD_LIST[*]}"
else
  THREADS_TO_RUN=("4")  # 默认 4 线程，不创建 threads-N 子目录
fi

TOTAL_PROFILES=${#PROFILES_TO_RUN[@]}
TOTAL_APIS=${#APIS_TO_RUN[@]}
echo "  Profiles: ${TOTAL_PROFILES}, APIs: ${TOTAL_APIS}, JDKs: ${#JDK_LIST[@]}, Thread variants: ${#THREADS_TO_RUN[@]}"

# ========== 端口可用性预检 ==========
check_port() {
  local port=$1
  if uname | grep -iq "mingw\|cygwin\|msys"; then
    netstat -ano 2>/dev/null | grep -qE ":${port}\s" && return 0 || return 1
  else
    timeout 1 bash -c "echo > /dev/tcp/127.0.0.1/$port" 2>/dev/null && return 0 || return 1
  fi
}

port_warn() {
  local port=$1
  echo "    [WARN] Port ${port} is already in use. Benchmark may fail with BindException."
  echo "    [WARN] Try: kill stale Java processes before running."
}

# ========== Step 1: 全量编译 ==========
echo ""
echo "[1/4] 全量编译所有模块..."
cd "$SCRIPT_DIR/.."
mvn clean install -DskipTests -q
if [ $? -ne 0 ]; then
  echo "[ERROR] 编译失败"
  exit 1
fi
cd "$SCRIPT_DIR"

# ========== Step 2: 构建各 profile classpath ==========
echo ""
echo "[2/4] 构建各 profile classpath..."
# classpath 放 benchmark-reports 下，避免被 mvn clean compile 删除
CP_DIR="benchmark-reports/.cp"
mkdir -p "$CP_DIR"
for ENTRY in "${PROFILES_TO_RUN[@]}"; do
  IFS=':' read -r PROFILE PORT BENCH_CLASS <<< "$ENTRY"
  echo "  [$PROFILE] classpath..."
  mvn -P"benchmark-$PROFILE" dependency:build-classpath \
    -Dmdep.outputFile="$CP_DIR/cp-$PROFILE.txt" -q
  if [ $? -ne 0 ]; then
    echo "    -> CLASSPATH FAIL for $PROFILE"
  fi
done

# ========== Step 3: 编译各 profile + 运行 benchmark 矩阵 ==========
echo ""
echo "[3/4] 编译各 profile 并运行 benchmark 矩阵..."
RUN_COUNT=0

for ENTRY in "${PROFILES_TO_RUN[@]}"; do
  IFS=':' read -r PROFILE PORT BENCH_CLASS <<< "$ENTRY"

  # 编译当前 profile（clean 防污染，确保 target/classes 只有当前 profile 的类）
  echo ""
  echo "  === Profile: $PROFILE (port $PORT) ==="
  mvn -P"benchmark-$PROFILE" clean compile -q
  if [ $? -ne 0 ]; then
    echo "    -> COMPILE FAIL for $PROFILE, skipping"
    continue
  fi

  # 读取 classpath（从 benchmark-reports 读取，不受 clean 影响）
  CP_FILE="$CP_DIR/cp-$PROFILE.txt"
  CP=$(head -1 "$CP_FILE" 2>/dev/null || echo "")
  if [ -z "$CP" ]; then
    echo "    -> CLASSPATH FILE MISSING: $CP_FILE, skipping"
    continue
  fi

  # 端口可用性检查
  if check_port "$PORT"; then
    port_warn "$PORT"
    if uname | grep -iq "mingw\|cygwin\|msys"; then
      pid=$(netstat -ano 2>/dev/null | grep ":${PORT}\s.*LISTENING" | awk '{print $NF}' | sort -u | head -1)
      if [ -n "$pid" ] && [ "$pid" != "0" ]; then
        echo "    [WARN] Killing process $pid on port $PORT..."
        taskkill //F //PID "$pid" 2>/dev/null && sleep 2
      fi
    fi
  fi

  for THREADS in "${THREADS_TO_RUN[@]}"; do
    # 设置线程参数
    THREAD_DIR=""
    THREAD_ARG=""
    if [ "$USE_THREAD_SUBDIRS" = true ]; then
      THREAD_DIR="threads-${THREADS}"
      THREAD_ARG="-Dbenchmark.threads=${THREADS}"
      echo ""
      echo "  === Thread count: ${THREADS} ==="
    fi

    for JAVA_CMD in "${JDK_LIST[@]}"; do
      # 解析 JDK 版本
      if [ "$JAVA_CMD" = "java" ]; then
        JDK_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2)
      else
        JDK_VERSION=$("$JAVA_CMD" -version 2>&1 | head -1 | cut -d'"' -f2)
      fi
      JDK_DIR_NAME="jdk-${JDK_VERSION// /_}"
      JDK_DIR_NAME="${JDK_DIR_NAME//:/_}"

      # 构建输出目录
      if [ -n "$THREAD_DIR" ]; then
        RESULTS_DIR="$REPORTS_DIR/$RUN_ID/$THREAD_DIR/$JDK_DIR_NAME"
      else
        RESULTS_DIR="$REPORTS_DIR/$RUN_ID/$JDK_DIR_NAME"
      fi
      mkdir -p "$RESULTS_DIR"

      # 判断 GC 日志格式
      if echo "$JDK_VERSION" | grep -q "^1\.8"; then
        GC_MODE="jdk8"
      else
        GC_MODE="jdk11"
      fi

      echo ""
      if [ -n "$THREAD_DIR" ]; then
        echo "  --- JDK: $JDK_VERSION (threads=$THREADS) -> $RESULTS_DIR ---"
      else
        echo "  --- JDK: $JDK_VERSION -> $RESULTS_DIR ---"
      fi

      # 确定运行的 API 列表：用户指定则逐个运行（独立 GC 日志），默认则一次性跑完
      if [ ${#APIS_LIST[@]} -gt 0 ]; then
        APIS_TO_RUN_SINGLE=("${APIS_LIST[@]}")
      else
        APIS_TO_RUN_SINGLE=("")
      fi

      for SINGLE_API in "${APIS_TO_RUN_SINGLE[@]}"; do
        if [ -n "$SINGLE_API" ]; then
          API_SUFFIX="-${SINGLE_API}"
          INCLUDE_ARG="-Dbenchmark.include=.*${BENCH_CLASS}\.${SINGLE_API}$"
          PROFILE_NAME="${PROFILE}-${SINGLE_API}"
          echo "    [benchmark] $PROFILE / $SINGLE_API..."
        else
          API_SUFFIX=""
          INCLUDE_ARG=""
          PROFILE_NAME="${PROFILE}"
          echo "    [benchmark] $PROFILE (all APIs)..."
        fi

        RUN_COUNT=$((RUN_COUNT + 1))

        # GC 日志参数（各 JVM 独立文件）
        GC_LOG_PATH="$(pwd)/$RESULTS_DIR/gc-${PROFILE}${API_SUFFIX}.log"
        if echo "$CP_SEP" | grep -q ";"; then
          if command -v cygpath &>/dev/null; then
            GC_LOG_PATH="$(cygpath -m "$GC_LOG_PATH")"
          fi
        fi
        if [ "$GC_MODE" = "jdk8" ]; then
          GC_ARG="-XX:+PrintGCDetails -Xloggc:${GC_LOG_PATH} -XX:+PrintGCDateStamps"
        else
          GC_ARG="-Xlog:gc*=info:file=${GC_LOG_PATH}:time,uptime,level,tags"
        fi

        "$JAVA_CMD" -cp "target/classes${CP_SEP}${CP}" \
          -Djmh.forks=1 \
          -Dbenchmark.port="$PORT" \
          -Dbenchmark.profile.name="${PROFILE_NAME}" \
          -Dbenchmark.output.dir="$(pwd)/$RESULTS_DIR" \
          -Dbenchmark.gc.log.arg="$GC_ARG" \
          ${THREAD_ARG} \
          ${INCLUDE_ARG} \
          ${EXTRA_JVM_ARGS:-} \
          io.springperf.benchmark.BenchmarkRunner

        if [ $? -eq 0 ]; then
          echo "    -> SUCCESS"
        else
          echo "    -> FAIL"
        fi
done
    done
  done
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
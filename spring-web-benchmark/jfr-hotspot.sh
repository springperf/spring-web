#!/bin/bash
#
# jfr-hotspot.sh — 一键 JFR CPU 热点分析
#
# 用法:
#   ./jfr-hotspot.sh --profiles perf,tomcat --apis get,json,async,sse
#   ./jfr-hotspot.sh --profiles perf --apis sse --threads 4
#
# 流程:
#   1. 调用 benchmark-all.sh 运行基准测试（自动添加 --jfr）
#   2. 将 JFR 文件复制到 benchmark-reports/jfr-cpu-hotspot-{datetime}/
#   3. 运行 CPU 热点分析并输出报告
#
# 输出:
#   benchmark-reports/jfr-cpu-hotspot-{datetime}/   ← JFR 文件
#   benchmark-reports/jfr-cpu-hotspot-{datetime}/hotspot-report.txt  ← 分析报告

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCHMARK_SCRIPT="$SCRIPT_DIR/benchmark-all.sh"
ANALYZE_SCRIPT="$SCRIPT_DIR/analyze-jfr2.sh"
REPORTS_DIR="$SCRIPT_DIR/benchmark-reports"

# 默认参数
PROFILES="perf,tomcat"
APIS="get,json,async,sse"
THREADS=""

# 解析参数
while [[ $# -gt 0 ]]; do
  case "$1" in
    --profiles)
      PROFILES="$2"
      shift 2
      ;;
    --apis)
      APIS="$2"
      shift 2
      ;;
    --api)
      APIS="$2"
      shift 2
      ;;
    --threads)
      THREADS="$2"
      shift 2
      ;;
    --thread-list)
      THREADS="$2"
      shift 2
      ;;
    -h|--help)
      echo "用法: $0 [--profiles perf,tomcat] [--apis get,json,async,sse] [--threads 4]"
      exit 0
      ;;
    *)
      echo "未知参数: $1"
      echo "用法: $0 [--profiles perf,tomcat] [--apis get,json,async,sse] [--threads 4]"
      exit 1
      ;;
  esac
done

echo "=========================================="
echo " JFR CPU 热点分析"
echo "=========================================="
echo "  Profiles: $PROFILES"
echo "  APIs:     $APIS"
if [ -n "$THREADS" ]; then
  echo "  Threads:  $THREADS"
fi
echo ""

# ========== Step 1: 运行 benchmark ==========
echo "[1/3] 运行基准测试 (JFR)..."

BENCHMARK_ARGS="--profiles $PROFILES --apis $APIS --jfr"
if [ -n "$THREADS" ]; then
  # 判断是单个线程数还是列表
  if [[ "$THREADS" == *","* ]]; then
    BENCHMARK_ARGS="$BENCHMARK_ARGS --thread-list $THREADS"
  else
    BENCHMARK_ARGS="$BENCHMARK_ARGS --threads $THREADS"
  fi
fi

cd "$SCRIPT_DIR"
# 捕获输出，同时显示在终端
OUTPUT_FILE=$(mktemp)
./benchmark-all.sh $BENCHMARK_ARGS 2>&1 | tee "$OUTPUT_FILE"
BENCHMARK_EXIT=${PIPESTATUS[0]}

if [ $BENCHMARK_EXIT -ne 0 ]; then
  echo "[ERROR] 基准测试失败"
  rm -f "$OUTPUT_FILE"
  exit 1
fi

# ========== Step 2: 提取最新结果目录，复制 JFR 文件 ==========
echo ""
echo "[2/3] 提取 JFR 文件..."

# 从 benchmark 输出中提取报告路径
REPORT_PATH=$(grep -oP '报告: \K.*' "$OUTPUT_FILE" | head -1)
rm -f "$OUTPUT_FILE"

if [ -z "$REPORT_PATH" ]; then
  # 回退：找最新修改的目录
  LATEST_DIR=$(ls -dt "$REPORTS_DIR"/2026*/ 2>/dev/null | head -1)
  if [ -z "$LATEST_DIR" ]; then
    echo "[ERROR] 找不到 benchmark 结果目录"
    exit 1
  fi
  RESULTS_DIR="$LATEST_DIR"
else
  RESULTS_DIR=$(dirname "$REPORT_PATH")
fi

echo "  结果目录: $RESULTS_DIR"

# 查找 JFR 文件（可能在 jdk-* 子目录中）
JFR_SOURCE_DIR=""
for SUBDIR in "$RESULTS_DIR" "$RESULTS_DIR"/*/; do
  if ls "$SUBDIR"/*.jfr 2>/dev/null > /dev/null; then
    JFR_SOURCE_DIR="$SUBDIR"
    break
  fi
done

if [ -z "$JFR_SOURCE_DIR" ]; then
  echo "[ERROR] 找不到 JFR 文件"
  ls -la "$RESULTS_DIR/"
  exit 1
fi

echo "  JFR 目录: $JFR_SOURCE_DIR"

# 创建目标目录
JFR_TAG="jfr-cpu-hotspot-$(date +"%Y%m%d-%H%M%S")"
JFR_TARGET_DIR="$REPORTS_DIR/$JFR_TAG"
mkdir -p "$JFR_TARGET_DIR"

# 复制 JFR 文件
cp "$JFR_SOURCE_DIR"/*.jfr "$JFR_TARGET_DIR/"

# 同时复制 JMH 结果（用于吞吐量汇总）
cp "$JFR_SOURCE_DIR"/jmh-results-*.json "$JFR_TARGET_DIR/" 2>/dev/null

JFR_COUNT=$(ls "$JFR_TARGET_DIR"/*.jfr 2>/dev/null | wc -l)
echo "  复制 $JFR_COUNT 个 JFR 文件到: $JFR_TARGET_DIR"

# ========== Step 3: 运行分析 ==========
echo ""
echo "[3/3] CPU 热点分析..."
echo ""

if [ -f "$ANALYZE_SCRIPT" ]; then
  bash "$ANALYZE_SCRIPT" "$JFR_TARGET_DIR" 2>&1 | tee "$JFR_TARGET_DIR/hotspot-report.txt"
else
  echo "  (分析脚本不存在，跳过)"
fi

echo ""
echo "=========================================="
echo " 完成!"
echo " JFR 文件: $JFR_TARGET_DIR"
echo " 分析报告: $JFR_TARGET_DIR/hotspot-report.txt"
echo "=========================================="
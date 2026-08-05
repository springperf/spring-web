#!/bin/bash
# JFR CPU热点分析脚本 (Windows兼容版)

JFR_DIR="$1"
if [ -z "$JFR_DIR" ]; then
    echo "用法: $0 <jfr-dir>"
    exit 1
fi

cd "$JFR_DIR" || exit 1

echo "========================================"
echo " JFR CPU热点分析报告"
echo " 目录: $(pwd)"
echo " 生成时间: $(date)"
echo "========================================"

TOTAL_FILES=$(ls *.jfr 2>/dev/null | wc -l)
echo " JFR文件数: $TOTAL_FILES"
echo ""

for JFR in *.jfr; do
    [ -f "$JFR" ] || continue
    echo "========================================"
    echo " 文件: $JFR"
    echo "========================================"
    
    # 文件大小
    SIZE_MB=$(ls -la "$JFR" | awk '{printf "%.1f", $5/1024/1024}')
    echo " 大小: ${SIZE_MB}MB"
    
    # Summary
    echo ""
    echo "--- 事件概要 ---"
    jfr summary "$JFR" 2>/dev/null | grep -E "ExecutionSample|CPULoad|ThreadAllocation|ObjectAllocationSample|GC pause" | head -10
    
    # CPU热点 - 提取栈顶方法
    # 从jfr print输出中提取栈顶方法行 (格式: "    java.net.SocketImpl.usePlainSocketImpl() line: 71")
    echo ""
    echo "--- Top CPU热点方法 (栈顶频率) ---"
    jfr print --events jdk.ExecutionSample --stack-depth 1 "$JFR" 2>/dev/null | \
        grep -E "line: [0-9]+$" | \
        sed 's/^[[:space:]]*//' | \
        sed 's/(.*) line: [0-9]*$//' | \
        sort | uniq -c | sort -rn | head -20
    
    # 线程状态分布
    echo ""
    echo "--- 线程状态分布 ---"
    jfr print --events jdk.ExecutionSample "$JFR" 2>/dev/null | \
        grep -E 'state = "' | \
        sed 's/^.*state = "//' | sed 's/".*$//' | \
        sort | uniq -c | sort -rn
    
    # 业务线程热点 (spring-perf / netty相关)
    echo ""
    echo "--- 业务线程热点 (spring-perf / netty相关) ---"
    jfr print --events jdk.ExecutionSample --stack-depth 3 "$JFR" 2>/dev/null | \
        grep -E "line: [0-9]+$" | \
        grep -iE "spring|perf|netty|tomcat" | \
        sed 's/^[[:space:]]*//' | \
        sed 's/(.*) line: [0-9]*$//' | \
        sort | uniq -c | sort -rn | head -15
    
    echo ""
done

# 从输出中提取基准测试结果
echo ""
echo "========================================"
echo " BENCHMARK 吞吐量汇总"
echo "========================================"
# 优先当前 JFR 目录（分析流程已把 jmh-results 复制到该目录）
JMH_FILES=$(find . -maxdepth 1 -name 'jmh-results-*.json' 2>/dev/null)
if [ -z "$JMH_FILES" ]; then
    # fallback: 上级目录取最新 run 目录 (2xxxxxxx-xxxxxx)，避免 find 遍历全部历史造成重复
    LATEST_RUN=$(ls -td "$(dirname "$(pwd)")"/2[0-9]* 2>/dev/null | head -1)
    if [ -n "$LATEST_RUN" ]; then
        JMH_FILES=$(find "$LATEST_RUN" -name 'jmh-results-*.json' 2>/dev/null)
    fi
fi
if [ -n "$JMH_FILES" ]; then
    # 提取所有 profile+api 组合
    while IFS= read -r JMH_FILE; do
        # 从文件名提取 container 和 api: jmh-results-perf-get.json
        BASENAME=$(basename "$JMH_FILE" .json)
        PART="${BASENAME#jmh-results-}"  # perf-get
        CONTAINER="${PART%%-*}"
        API="${PART#*-}"
        SCORE=$(grep -o '"score" : [0-9.]*' "$JMH_FILE" | head -1 | sed 's/"score" : //')
        ERROR=$(grep -o '"scoreError" : [0-9.]*' "$JMH_FILE" | head -1 | sed 's/"scoreError" : //')
        echo "  $CONTAINER / $API: $SCORE +- $ERROR ops/s"
    done <<< "$JMH_FILES"
else
    echo "  (未找到 JMH 结果文件)"
fi

echo ""
echo "分析完成!"

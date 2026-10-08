#!/bin/bash
# WSL 环境自检：JDK / Maven / 离线依赖 / 仓库内脚本可用性
#
# 用途：跑 wsl-abba2.sh / wsl-jfr.sh 之前先确认 WSL 侧环境就绪。
# 用法: wsl-check.sh [仓库根目录的 WSL 路径]
set -uo pipefail

# 可从外部覆盖（wsl-perf-server.sh 用同名变量，保持一致）
WSL_JAVA_HOME="${WSL_JAVA_HOME:-/home/hcd/jdk17}"
WSL_MVN_REPO="${WSL_MVN_REPO:-/mnt/d/maven/repository}"
WSL_MVN="${WSL_MVN:-mvn}"
REPO_WSL="${1:-}"

if [ -z "$REPO_WSL" ]; then
  # 从本脚本位置推导：<repo>/spring-web-benchmark/perf/ -> <repo>
  SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  GIT_WSL="$(cd "$SELF" && git rev-parse --show-toplevel 2>/dev/null)"
  REPO_WSL="${GIT_WSL:-/mnt/d/workspace/springperf/spring-web}"
fi

export JAVA_HOME="$WSL_JAVA_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
export MAVEN_OPTS="-Xmx512m"

echo "=== 1. JDK ==="
java -version 2>&1 | head -2

echo "=== 2. Maven ==="
"$WSL_MVN" -v 2>&1 | head -1

echo "=== 3. 离线仓库 ==="
if [ -d "$WSL_MVN_REPO" ]; then
  echo "  ✓ $WSL_MVN_REPO"
else
  echo "  ✗ 不存在: $WSL_MVN_REPO（见 scripts/WSL_SETUP.md）"
fi

echo "=== 4. 仓库 ==="
if [ -d "$REPO_WSL" ]; then
  echo "  ✓ $REPO_WSL"
  ls "$REPO_WSL"/spring-web-benchmark/target/cp-perf.txt >/dev/null 2>&1 \
    && echo "  ✓ classpath 文件已生成" \
    || echo "  ⚠ 缺 target/cp-perf.txt —— 先跑 perf/measure-alloc.sh会自动生成，或手动:"
  echo "     cd <repo> && mvn -o -pl spring-web-benchmark -Pbenchmark-perf compile"
else
  echo "  ✗ 路径不存在: $REPO_WSL"
fi

echo "=== 5. perf 脚本 ==="
for s in wsl-perf-server.sh wsl-abba2.sh wsl-jfr.sh; do
  [ -f "$REPO_WSL/spring-web-benchmark/perf/$s" ] && echo "  ✓ $s" || echo "  ✗ $s"
done

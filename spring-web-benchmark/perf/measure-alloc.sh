#!/usr/bin/env bash
# 单版服务端分配量测量（TLAB 精确计数，误差 ~0.6%）
#
# 原理：用 com.sun.management.ThreadMXBean.getThreadAllocatedBytes 按线程 ID
# 读 EventLoop 线程的累计分配字节数。这是**精确计数**而非采样，无置信区间问题。
# 相比吞吐（离散度 5~20%）是唯一可用于版本间对比的指标。
#
# 用法: measure-alloc.sh <标签> [端口]
#   例: measure-alloc.sh baseline 14101
#
# 前置：已实现 ServerAllocBenchmark（spring-web-benchmark/.../server/）并编译，
#      且已生成 classpath 文件 target/cp-perf.txt。
# 方法论见 docs/feature/performance-analysis-methodology.md §1
set -uo pipefail
source "$(dirname "$0")/perf-env.sh"

TAG="${1:?需要标签（写入报告文件名）}"
PORT="${2:-14100}"
# 基线 worktree 路径（可选）。设置后测的是基线代码的分配量，
# 用其自身的 target/classes 与 classpath 文件，避免被当前版遮蔽。
BASELINE="${3:-${BASELINE_WT:-}}"

# 堆 1g 与项目既有基准一致。注意：**不要**加 -XX:+UseParallelGC
#（会让 Netty 基准跑不出结果，见方法论 §2.5）
JVM_OPTS="-Xms1g -Xmx1g -XX:+UseG1GC -XX:+AlwaysPreTouch"

REPORT="$PERF_OUT/server-alloc-$TAG.json"
REPORT_WIN="$PERF_OUT_WIN/server-alloc-$TAG.json"   # Windows 侧 python 用
mkdir -p "$PERF_OUT"
perf_clear_jmh_lock
rm -f "$REPORT"

if [ -n "$BASELINE" ]; then
  [ -d "$BASELINE" ] || { echo "ERROR: 基线路径不存在: $BASELINE" >&2; exit 1; }
  BW="$(cd "$BASELINE" && pwd -W)"
  BCLS=""
  for m in $PERF_MODULES; do BCLS="${BCLS}${BCLS:+;}$BW/$m/target/classes"; done
  BCP_FILE="$BW/spring-web-benchmark/target/cp-base.txt"
  [ -f "$BCP_FILE" ] || { echo "ERROR: 基线缺 $BCP_FILE（先在该 worktree 编译 benchmark）" >&2; exit 1; }
  CP="${BCLS};$BW/spring-web-benchmark/target/classes;$(cat "$BCP_FILE");$SERVLET_JAR"
  echo "  [基线] $BASELINE"
else
  CP=$(perf_classpath) || exit 1
fi
cd "$BENCH_DIR" || exit 1

# -f 1单 fork（多次会覆盖报告）
# -wi 3 -i 1  预热 3 次、测量 1 次：基准内部自己做 5 轮采样取中位数
#            （@AuxCounters 在 @State(Scope.Benchmark) 下输出空表，故不走 JMH 报表）
# -Dbenchmark.alloc.out.dir 必须显式传：基准默认写到「工作目录/target/benchmark-reports」，
#            而工作目录随调用方式变化
"$JDK17/bin/java" -cp "$CP" org.openjdk.jmh.Main "ServerAllocBenchmark" \
  -f 1 -wi 3 -i 1 -r 1s -w 1s \
  -jvmArgsAppend "-Dbenchmark.port=$PORT -Dbenchmark.profile.name=$TAG -Dbenchmark.alloc.out.dir=$PERF_OUT_WIN $JVM_OPTS" 2>&1 \
  | grep -aE "zero delta|Exception|ERROR" | head -5

[ -f "$REPORT" ] || { echo "ERROR: 报告未生成 -> $REPORT" >&2; exit 1; }

"$PYEXE" - "$REPORT_WIN" "$TAG" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])); tag = sys.argv[2]
for k in ('json', 'get', 'bytes'):
    key = f'{k}.median'
    if key not in d:
        continue
    s, m = d[f'{k}.samples'], d[key]
    spread = (max(s) - min(s)) / m * 100 if m else 0
    print(f'  [{tag}] {k:6s} median={m:6d} B/op  离散={spread:5.2f}%  samples={s}')
print(f'  [{tag}] threads: {d.get("serverThreads", "?")}')
PY
echo "  [$TAG] 报告: $REPORT"

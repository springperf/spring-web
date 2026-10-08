#!/usr/bin/env bash
# 量化「内联失败」的 CPU 代价：临时调高 FreqInlineSize/MaxInlineSize，
# 看吞吐是否随之变化。若无变化 => 这些大方法本就不在关键路径，
# 内联线索可放下；若显著变化 => 值得拆分。
#
# 用法: inlining-ab.sh [rounds]
set -uo pipefail
source "$(dirname "$0")/perf-env.sh"
export MSYS_NO_PATHCONV=1

ROUNDS="${1:-4}"
P=15300

CP=$(perf_classpath "$BENCH_DIR/target/cp-base.txt") || exit 1

# 阈值档位：默认(325/35) → 中(600/100) → 高(2000/500)
LEVELS=("DEFAULT" "MID" "HIGH")
JVM_OPTS=(
  "-XX:FreqInlineSize=325 -XX:MaxInlineSize=35"
  "-XX:FreqInlineSize=600 -XX:MaxInlineSize=100"
  "-XX:FreqInlineSize=2000 -XX:MaxInlineSize=500"
)

run() { # $1=索引
  local idx=$1 opt="${JVM_OPTS[$1]}"
  P=$((P+1))
  ( cd "$BENCH_DIR" && "$JDK17/bin/java" -cp "$CP" org.openjdk.jmh.Main "PerfBenchmark.json" \
      -f 1 -wi 3 -i 4 -r 4s -w 3s \
      -jvmArgsAppend "-Dbenchmark.port=$P $opt" 2>&1 ) \
    | tr '
' '
' | grep -aE "PerfBenchmark[.]json[[:space:]]+thrpt" | awk '{print $4}'
}

perf_clear_jmh_lock
for i in 0 1 2; do : > "$PERF_OUT/inl-$i.txt"; done

echo "### 内联阈值梯度 ABBA x$ROUNDS"
echo "  DEFAULT: FreqInlineSize=325 MaxInlineSize=35（HotSpot 默认）"
echo "  MID:600/100   HIGH:2000/500"

for r in $(seq 1 "$ROUNDS"); do
  for i in 0 1 2; do
    v=$(run "$i")
    echo "  r$r  ${LEVELS[$i]:0:4}=$v"
    echo "$v" >> "$PERF_OUT/inl-$i.txt"
  done
done

"$PYEXE" - "$PERF_OUT_WIN" <<'PY'
import statistics, sys
names = ('DEFAULT', 'MID    ', 'HIGH  ')
sets = []
for i in range(3):
    try:
        sets.append([float(x) for x in open(f'{sys.argv[1]}/inl-{i}.txt').read().split()])
    except Exception:
        sets.append([])
n = min(len(s) for s in sets) if all(sets) else 0
if n == 0:
    print('数据不足'); raise SystemExit
print(f'\n### 汇总（n={n}）')
base = None
for i, d in enumerate(sets):
    m = statistics.median(d)
    if base is None: base = m
    print(f'  {names[i]} median={m:9.1f}  min={min(d):9.1f}  max={max(d):9.1f}  '
          f'极差={((max(d)-min(d))/m*100):5.1f}%  vs DEFAULT={((m/base-1)*100):+5.2f}%')
PY

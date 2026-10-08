#!/usr/bin/env bash
# 测固定堆配置下的标准差：同一配置重复 N 轮（**不切版本**）
#
# 用途：判断「当前测量口径能否分辨目标效应量」。**任何吞吐对比之前都应先跑这个。**
# 实测参考：in-process 1g → sd 10.1%；2g → sd 20.3%（堆越大噪声越大）；
#           WSL 分离模式 → sd 5.3%（唯一可用口径，见 wsl-abba2.sh）
#
# 用法: heap-noise.sh [堆大小] [轮数]
#   例: heap-noise.sh 1g 6
#
# 方法论见 docs/feature/performance-analysis-methodology.md §2.1
set -uo pipefail
source "$(dirname "$0")/perf-env.sh"

HS="${1:-1g}"
ROUNDS="${2:-6}"
PORT=15600

# 注意：**不要**加 -XX:+UseParallelGC / -XX:-TieredCompilation
#（前者与 Netty 不兼容致结果表为空，后者预热期过长跑不完 —— 方法论 §2.5）
JVM_OPTS="-Xms$HS -Xmx$HS -XX:+AlwaysPreTouch"

CP=$(perf_classpath "$BENCH_DIR/target/cp-base.txt") || exit 1
mkdir -p "$PERF_OUT"
OUT="$PERF_OUT/heap-$HS.txt"
OUT_WIN="$PERF_OUT_WIN/heap-$HS.txt"   # Windows 侧 python 用
OUT_WIN="$PERF_OUT_WIN/heap-$HS.txt"
: > "$OUT"
perf_clear_jmh_lock

cd "$BENCH_DIR" || exit 1
echo "### 固定堆 ${HS} 重复 $ROUNDS 轮（同一配置，不切版本）"
for r in $(seq 1 "$ROUNDS"); do
  PORT=$((PORT+1))
  v=$("$JDK17/bin/java" -cp "$CP" org.openjdk.jmh.Main "PerfBenchmark.json" \
        -f 1 -wi 3 -i 4 -r 4s -w 3s \
        -jvmArgsAppend "-Dbenchmark.port=$PORT $JVM_OPTS" 2>&1 \
      | tr '
' '
' | grep -aE "PerfBenchmark[.]json[[:space:]]+thrpt" | awk '{print $4}')
  echo "  r$r  $v"
  echo "$v" >> "$OUT"
done

"$PYEXE" - "$OUT_WIN" "$HS" <<'PY'
import statistics as s, sys
d = [float(x) for x in open(sys.argv[1]).read().split()]
if len(d) < 2:
    print('数据不足'); raise SystemExit
m, md = s.mean(d), s.median(d)
sd = s.stdev(d); se = sd / len(d) ** 0.5
print(f'\n### {sys.argv[2]} 堆 n={len(d)}')
print(f'  mean={m:9.1f}  median={md:9.1f}')
print(f'  sd   ={sd:8.1f}  ({sd/m*100:4.1f}%)')
print(f'  极差  ={((max(d)-min(d))/md*100):5.1f}%')
print(f'  SE   ={se:8.1f}  95%CI=±{1.96*se/m*100:4.1f}%')
print(f'\n  可分辨的最小效应量（双侧 t=2.776, 配对差值 sd≈1.5×sd）:')
for eff in (0.10, 0.05, 0.03, 0.02, 0.01):
    need = next((k for k in range(4, 300)
                 if abs(m * eff / (1.5 * sd / k**0.5)) > 2.776), '>300')
    print(f'    {eff*100:>4.0f}%  需 {need:>4} 轮')
print('\n  注：sd>10% 时该口径不适用于版本对比（噪声与版本差异同阶）')
PY

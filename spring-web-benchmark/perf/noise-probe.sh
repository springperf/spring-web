#!/usr/bin/env bash
# 测量噪声抑制参数的效果：同一配置重复 4 轮，比较 sd / 极差
# 用法: noise-probe.sh [rounds]
set -uo pipefail
source "$(dirname "$0")/perf-env.sh"
export MSYS_NO_PATHCONV=1

SJ="D:/maven/repository/jakarta/servlet/jakarta.servlet-api/6.1.0/jakarta.servlet-api-6.1.0.jar"
ROUNDS="${1:-4}"
P=15400

CLS=""
CP=$(perf_classpath "$BENCH_DIR/target/cp-base.txt") || exit 1

# 噪声抑制组合：关分层编译（只用 C2，消除 C1/OSR 时机差异）、固定堆、
# ParallelGC（无并发 GC 线程，减少调度噪声）
NORMAL="-Xms1g -Xmx1g -XX:+UseG1GC -XX:+AlwaysPreTouch"
QUIET="-Xms2g -Xmx2g -XX:+AlwaysPreTouch -XX:CICompilerCount=2 -XX:+UseParallelGC"

run() { # $1=描述 $2=额外jvm 参数
  local tag="$1" opts="$2"
  P=$((P+1))
  ( cd "$BENCH_DIR" && "$JDK17/bin/java" -cp "$CP" org.openjdk.jmh.Main "PerfBenchmark.json" \
      -f 1 -wi 3 -i 4 -r 4s -w 3s \
      -jvmArgsAppend "-Dbenchmark.port=$P $opts" 2>&1 ) \
    | tr '
' '
' | grep -aE "PerfBenchmark[.]json[[:space:]]+thrpt" | awk '{print $4}'
}

perf_clear_jmh_lock
: > "$PERF_OUT"/np-normal.txt; : > "$PERF_OUT"/np-quiet.txt

echo "### 噪声抑制对比 x$ROUNDS"
for r in $(seq 1 "$ROUNDS"); do
  a=$(run "normal" "$NORMAL"); echo "  r$r  NORMAL=$a"; echo "$a" >> "$PERF_OUT"/np-normal.txt
  b=$(run "quiet"  "$QUIET");  echo "  r$r  QUIET =$b"; echo "$b" >> "$PERF_OUT"/np-quiet.txt
done

"$PYEXE" - "$PERF_OUT_WIN" <<'PY'
import statistics as s, sys
print('\n### 噪声对比')
for tag, f in (('NORMAL(1g/G1/分层) ', 'np-normal.txt'),
               ('QUIET (2g/Parallel/C2线程固定)', 'np-quiet.txt')):
    d = [float(x) for x in open(f'{sys.argv[1]}/{f}').read().split()]
    m, md, sd = s.mean(d), s.median(d), s.stdev(d) if len(d) > 1 else 0
    se = sd / len(d) ** 0.5 if len(d) > 1 else 0
    print(f'  {tag} n={len(d)} mean={m:8.1f} sd={sd:7.1f}({sd/m*100:4.1f}%) '
          f'极差={((max(d)-min(d))/md*100):5.1f}% SE={se:6.1f} 95CI=±{1.96*se/m*100:4.1f}%')
PY

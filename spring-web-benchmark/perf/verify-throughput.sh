#!/usr/bin/env bash
# 方案 A 的吞吐收益验证：高样本量 in-process ABBA
#   WITH = e4a26df（含 getWebComponent 快路径）
#   WITHOUT = e4a26df^（回退该提交）
# 每轮只跑 PerfBenchmark.json（吞吐），rounds 轮交替，单次 fork
set -uo pipefail
source "$(dirname "$0")/perf-env.sh"
export MSYS_NO_PATHCONV=1

SJ="D:/maven/repository/jakarta/servlet/jakarta.servlet-api/6.1.0/jakarta.servlet-api-6.1.0.jar"
ROUNDS="${1:-6}"
P=15100
WITH=HEAD
WITHOUT=HEAD~1

CP=$(perf_classpath "$BENCH_DIR/target/cp-base.txt") || exit 1

LOMBOK=$(find /d/maven/repository -name "lombok-1.18*.jar" ! -name "*sources*" ! -name "*javadoc*" | head -1)

checkout() { # $1=rev  —— 切 WebComponentContainer 到指定版本并重编译
  # git checkout + javac，不用 mvn：mvn 在本脚本的子 shell 里无法解析
  # plexus classpath（ClassNotFoundException: ...Launcher），而本实验只需编译
  # 单个文件，javac + 已有 classpath 足够（需显式指定 lombok 处理器，
  # 否则 @Slf4j 生成的 log 字段缺失）
( cd "$REPO_ROOT" || exit 1
    git checkout -q "$1" -- spring-web/src/main/java/io/springperf/web/context/WebComponentContainer.java || exit 1
    if grep -q "hits == 1" spring-web/src/main/java/io/springperf/web/context/WebComponentContainer.java; then
      SRC=WITH; else SRC=WITHOUT; fi
    /d/soft_space/Java/jdk-17.0.9/bin/javac -nowarn -encoding UTF-8 \
      -cp "spring-web/target/classes;$(cat spring-web-benchmark/target/cp-perf.txt)" \
      -processorpath "$LOMBOK" -d spring-web/target/classes \
      spring-web/src/main/java/io/springperf/web/context/WebComponentContainer.java 2>&1 | grep -a ERROR | head -3
    echo "    [src=$SRC compiled]" )
}

run() { # $1=标签
  P=$((P+1))
  ( cd "$BENCH_DIR" && "$JDK17/bin/java" -cp "$CP" org.openjdk.jmh.Main "PerfBenchmark.json" \
      -f 1 -wi 3 -i 4 -r 4s -w 3s \
      -jvmArgsAppend "-Dbenchmark.port=$P" 2>&1 ) \
    | tr '
' '
' | grep -aE "PerfBenchmark[.]json[[:space:]]+thrpt" | awk '{print $4}'
}

perf_clear_jmh_lock
: > "$PERF_OUT"/t_with.txt; : > "$PERF_OUT"/t_without.txt
echo "### 方案A 吞吐 ABBA x$ROUNDS（单 fork，4s x 4 迭代）"

for r in $(seq 1 "$ROUNDS"); do
  checkout "$WITHOUT"; v=$(run "w/o"); echo "  r$r  WITHOUT=$v"; echo "$v" >> "$PERF_OUT"/t_without.txt
  checkout "$WITH";    v=$(run "w/");    echo "  r$r  WITH   =$v"; echo "$v" >> "$PERF_OUT"/t_with.txt
done

# 恢复原状（保持在 WITH）
checkout "$WITH" >/dev/null

"$PYEXE" - "$PERF_OUT_WIN" <<'PY'
import statistics, sys
def load(p):
    try: return [float(x) for x in open(p).read().split()]
    except: return []
w, wo = load(f'{sys.argv[1]}/t_with.txt'), load(f'{sys.argv[1]}/t_without.txt')
n = min(len(w), len(wo))
if n == 0: print("数据不足"); raise SystemExit
w, wo = w[:n], wo[:n]
print(f"\n### 汇总（n={n}）")
for tag, d in (("WITHOUT", wo), ("WITH", w)):
    m = statistics.median(d)
    print(f"  {tag:8s} median={m:9.1f}  min={min(d):9.1f}  max={max(d):9.1f}  "
          f"极差={((max(d)-min(d))/m*100):5.1f}%  stdev={statistics.stdev(d):7.1f}")
mw, mo = statistics.median(w), statistics.median(wo)
print(f"\n  WITH/WITHOUT = {mw/mo:.4f}  ->  {(mw/mo-1)*100:+.2f}%")
# 符号检验：WITH 更高的轮次数
win = sum(1 for a, b in zip(w, wo) if a > b)
print(f"  逐轮胜出: WITH {win}/{n} 轮")
PY

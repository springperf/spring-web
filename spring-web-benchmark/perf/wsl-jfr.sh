#!/bin/bash
# WSL 服务端 JFR 录制：服务端在 WSL 跑，Windows 侧 JMH 客户端打负载
# 用法: wsl-jfr.sh <worktree> <标签> [rounds] [wi] [i] [wl]
#   worktree: 仓库根目录，或另建的 worktree（用于版本对比）
#   标签:      写入文件名，如 cur / base
set -uo pipefail
source "$(dirname "$0")/perf-env.sh"
export MSYS_NO_PATHCONV=1

WT="${1:?需要 worktree}"
TAG="${2:-cur}"
ROUNDS="${3:-1}"
WI="${4:-3}"
IT="${5:-4}"
WL="${6:-4s}"

JCMD="${WSL_JCMD:-/home/hcd/jdk17/bin/jcmd}"
PORT="${WSL_JFR_PORT:-9092}"
OUTWIN="$PERF_OUT/wsl-jfr"
OUTWSL="${PERF_OUT_WIN}/wsl-jfr"
mkdir -p "$OUTWIN"

# classpath：worktree 可能是仓库本身，也可能是另建的 worktree（版本对比）
NAME=$(basename "$WT")
CP_FILE="$BENCH_DIR/target/cp-perf.txt"
BENCH_CLS="$WIN_REPO/spring-web-benchmark/target/classes"
CLS="$(perf_module_classpath)"

perf_clear_jmh_lock
WSL_IP=$(wsl -d Ubuntu -- hostname -I 2>/dev/null | tr -d '\0' | awk '{print $1}')
echo "### 录制服务端 JFR  tag=$TAG  server=WSL $WSL_IP:$PORT  rounds=$ROUNDS wi=$WI i=$IT wl=$WL"

srv_stop() { wsl -d Ubuntu -- bash "$SRV_WSL" stop 2>&1 | tr -d '\0'; }
srv_start() { wsl -d Ubuntu -- bash "$SRV_WSL" start "$1" "$PORT" 768 2>&1 | tr -d '\0' | tail -2; }
PID() { wsl -d Ubuntu -- "$JCMD" -l 2>/dev/null | tr -d '\0' | grep -a PerfApplication | awk '{print $1}' | head -1; }

for r in $(seq 1 "$ROUNDS"); do
  srv_stop
  srv_start "$WT"
  SPID=$(PID)
  [ -n "$SPID" ] || { echo "  ✗ 拿不到服务端 PID"; exit 1; }
  echo "  服务端 PID=$SPID"

  JFR=/tmp/wsl-${TAG}-r${r}.jfr
  # WSL2 下 JFR 拿不到 perf event，Java 采样退化到极低频（438 vs Native 6440）。
  # 显式把 ExecutionSample 压到 1ms，并同时录 NativeMethodSample 对照。
  wsl -d Ubuntu -- "$JCMD" "$SPID" JFR.start name=srv settings=profile \
      jdk.ExecutionSample#enabled=true \
      jdk.ExecutionSample#period=1ms \
      jdk.NativeMethodSample#enabled=true \
      jdk.NativeMethodSample#period=1ms \
      filename="$JFR" 2>&1 | tr -d '\0' | sed 's/^/    /'
  sleep 2

  out=$(cd "$BENCH_DIR" && "$JDK17/bin/java" -cp "$CLS;$BENCH_CLS;$(cat "$CP_FILE");$SERVLET_JAR" \
      org.openjdk.jmh.Main PerfBenchmark.json -f 1 -wi "$WI" -i "$IT" -r "$WL" -w 3s -prof gc \
      -jvmArgsAppend "-Dbenchmark.target=$WSL_IP -Dbenchmark.port=$PORT" 2>&1)
  echo "$out" | grep -aE "PerfBenchmark[.]json[[:space:]]+thrpt" | sed "s/^/  r$r  /"

  wsl -d Ubuntu -- "$JCMD" "$SPID" JFR.dump name=srv filename="$JFR" 2>&1 | tr -d '\0' | sed 's/^/    /'
  wsl -d Ubuntu -- "$JCMD" "$SPID" JFR.stop name=srv >/dev/null 2>&1
  wsl -d Ubuntu -- cp "$JFR" "$OUTWSL/wsl-${TAG}-r${r}.jfr"
  echo "  -> $OUTWIN/wsl-${TAG}-r${r}.jfr"
done
srv_stop
ls -la "$OUTWIN"
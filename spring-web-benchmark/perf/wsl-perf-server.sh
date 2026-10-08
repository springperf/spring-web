#!/bin/bash
# 在 WSL 内构建并启动 perf 服务端（供 Windows 侧 JMH 客户端连接）
#
# 与 scripts/wsl-server.sh 的区别：那个按 profile 启动（面向对外基准），
# 这个支持**切worktree**（消融实验必需 —— 要在同一台机器上跑不同代码版本）
# 并提供 stop 子命令。
#
# 用法（Windows Git Bash）:
#   ./wsl-perf-server.sh start <worktree路径> <port> <heapMB>
#   ./wsl-perf-server.sh stop
#
# <worktree路径> 用 Git Bash 风格（/d/...），内部自动转 WSL 风格（/mnt/d/...）
#
# 前置（见 scripts/WSL_SETUP.md）:
#   - WSL 内装JDK 17，路径经WSL_JAVA_HOME 指定（默认 /home/hcd/jdk17）
#   - 离线 maven 仓库 WSL_MVN_REPO（默认 /mnt/d/maven/repository）
#   - Maven 可执行 WSL_MVN（默认 mvn，需在 PATH 中）
set -uo pipefail

# ---- 环境（可从外部覆盖）-------------------------------------------------
# WSL 会**继承 Windows 的 PATH**（/mnt/c/...），其中含空格（"C:/Program Files"），
# 直接 export 到 Linux 的 PATH 会报 `not a valid identifier` 而整条命令失败。
# 故先重置为最小 PATH，再逐项加需要的东西。
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

WSL_JAVA_HOME="${WSL_JAVA_HOME:-/home/hcd/jdk17}"
WSL_MVN_REPO="${WSL_MVN_REPO:-/mnt/d/maven/repository}"
WSL_MVN_BIN="${WSL_MVN_BIN:-/mnt/d/soft_space/apache-maven-3.8.6/bin}"

APP_CLASS="io.springperf.benchmark.app.PerfApplication"
# 传给 mvn 的跳过开关：消融场景只关心分配量，跳过静态检查与质量门禁
MVN_SKIP="-Dspotbugs.skip=true -Dformatter.skip=true -Dmodernizer.skip=true -Denforcer.skip=true"

# maven 可执行：优先 WSL_MVN_BIN 下的 mvn（显式路径最可靠），否则用 PATH 上的
if [ -x "$WSL_MVN_BIN/mvn" ]; then
  WSL_MVN="$WSL_MVN_BIN/mvn"
elif command -v mvn >/dev/null 2>&1; then
  WSL_MVN=mvn
else
  echo "ERROR: WSL 内找不到 maven。设WSL_MVN_BIN 指向其 bin 目录（见 scripts/WSL_SETUP.md）" >&2
  exit 1
fi

CMD="${1:-start}"

# ---- stop（无需路径参数）-------------------------------------------------
if [ "$CMD" = "stop" ]; then
  pkill -f "$APP_CLASS" 2>/dev/null && echo "  已停止服务端" || echo "  无运行中的服务端"
  sleep 2
  exit 0
fi

WT="${2:?需要 worktree 路径}"
PORT="${3:-9092}"
HEAP="${4:-768}"

# Git Bash 的 /d/x → WSL 的 /mnt/d/x
to_wsl_path() {
  case "$1" in
    /mnt/*) echo "$1" ;;
    /[a-z]/*) echo "/mnt$1" ;;
    *) echo "$1" ;;
  esac
}
PROJ="$(to_wsl_path "$WT")"
[ -d "$PROJ" ] || { echo "ERROR: 路径不存在: $PROJ" >&2; exit 1; }

export JAVA_HOME="$WSL_JAVA_HOME"
[ -x "$JAVA_HOME/bin/java" ] || { echo "ERROR: JAVA_HOME 无效: $JAVA_HOME" >&2; exit 1; }
BENCH="$PROJ/spring-web-benchmark"

echo "==> 构建 $PROJ 的 benchmark + 兄弟模块"
cd "$PROJ" || exit 1
"$WSL_MVN" -o -q -pl spring-web-benchmark -am -Pbenchmark-perf install -DskipTests \
    -Dmaven.repo.local="$WSL_MVN_REPO" $MVN_SKIP \
    --batch-mode --no-transfer-progress 2>&1 | tail -5
[ -d "$BENCH/target/classes" ] || { echo "ERROR: 构建失败（maven 输出见上）" >&2; exit 1; }

# ---- 生成 WSL 侧 classpath -----------------------------------------------
echo "==> 生成 WSL classpath"
(cd "$BENCH" && "$WSL_MVN" -o -q -Pbenchmark-perf dependency:build-classpath \
    -Dmdep.outputFile=target/cp-wsl-raw.txt -Dmdep.pathSeparator=';' \
    -Dmaven.repo.local="$WSL_MVN_REPO" --batch-mode --no-transfer-progress 2>&1 | tail -2)

# 关键：dependency:build-classpath 产出的是 Windows 分隔符 ';'，
# 而 Linux JVM 的 classpath 只认 ':'，必须转换。
CP_RAW=$(cat "$BENCH/target/cp-wsl-raw.txt")
CP_WSL=$(echo "$CP_RAW" | tr ';' ':')

echo "==> 启动服务端（端口 $PORT，堆 ${HEAP}m）"
# stackdepth=1024：JFR 默认 64帧会截断深栈样本（外层帧被砍，归因失真）
nohup "$JAVA_HOME/bin/java" \
  -Xms${HEAP}m -Xmx${HEAP}m -XX:+UseG1GC -XX:+AlwaysPreTouch \
  -XX:FlightRecorderOptions=stackdepth=1024 \
  -cp "${CP_WSL}:$BENCH/target/classes" \
  "$APP_CLASS" --server.port=${PORT} \
  > "/tmp/perf-server-${PORT}.log" 2>&1 &

# ---- 等待就绪：用端口探测而非日志关键字 ----
# 不能靠 grep "Started PerfApplication"：benchmark 的 application.properties 把
# logging.level.root 设为WARN，该行根本不会输出（实测日志仅 14 行，全是 WARN）。
# 端口 LISTEN 才是可靠的就绪信号。
for i in $(seq 1 60); do
  if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
    exec 3<&- 2>/dev/null; exec 3>&- 2>/dev/null
    echo "  ✓ 服务端已就绪（${i}s，端口 $PORT 已监听）"; break
  fi
  if grep -qE "APPLICATION FAILED TO START|BUILD FAILURE|Error occurred" \
       /tmp/perf-server-${PORT}.log 2>/dev/null; then
    echo "ERROR: 启动失败："; tail -20 "/tmp/perf-server-${PORT}.log"; exit 1
  fi
  if ! kill -0 $! 2>/dev/null && [ $i -gt 5 ]; then
    echo "ERROR: 服务端进程已退出："; tail -20 "/tmp/perf-server-${PORT}.log"; exit 1
  fi
  sleep 1
done
tail -2 "/tmp/perf-server-${PORT}.log"

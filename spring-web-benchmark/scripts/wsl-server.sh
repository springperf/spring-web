#!/usr/bin/env bash
# =============================================================================
# WSL external 模式：构建并启动被测服务端（服务端跑在 WSL 内，受 .wslconfig 资源限制）。
#
# 用法（Windows Git Bash）:
#   ./scripts/wsl-server.sh [profile] [port] [heap_mb]
#     例: ./scripts/wsl-server.sh benchmark-perf 9092 768
#
# 前置条件:
#   - WSL 内已装 JDK 17（推荐，JFR 需要），JAVA_HOME 指向它
#   - %UserProfile%\.wslconfig 已配置（见 scripts/WSL_SETUP.md）:
#       [wsl2]
#       processors=4
#       memory=2GB
#   - Maven 在 Windows PATH 中
#
# 产物:
#   - WSL 内 /tmp/<profile>-server.jfr  （服务端 JFR 记录，ctrl+c 停止时落盘）
#
# 注意:
#   - 不拷贝产物到 WSL，直接经 /mnt/c 访问 Windows 构建产物（启动慢一点，运行时无影响）
#   - heap_mb 默认 768：配合 .wslconfig memory=2GB（实测配置），留余量给 Metaspace/直接内存
# =============================================================================
set -euo pipefail
# 脚本位于 <project>/spring-web-benchmark/scripts/
cd "$(dirname "$0")/.."
BENCH="$(pwd)"                 # <project>/spring-web-benchmark 模块目录
PROJECT="$(dirname "$BENCH")"  # <project> 项目根（聚合 POM 所在，mvn -pl 必须在此执行）

PROFILE="${1:-benchmark-perf}"
PORT="${2:-9092}"
HEAP_MB="${3:-768}"

case "$PROFILE" in
  benchmark-perf)         APP=io.springperf.benchmark.app.PerfApplication ;;
  benchmark-perf-support) APP=io.springperf.benchmark.app.PerfSupportApplication ;;
  benchmark-tomcat)       APP=io.springperf.benchmark.app.TomcatApplication ;;
  benchmark-undertow)     APP=io.springperf.benchmark.app.UndertowApplication ;;
  benchmark-webflux)      APP=io.springperf.benchmark.app.WebFluxApplication ;;
  *) echo "未知 profile: $PROFILE（可选: benchmark-perf / benchmark-perf-support / benchmark-tomcat / benchmark-undertow / benchmark-webflux）"; exit 1 ;;
esac

echo "==> [1/3] 打包 $PROFILE（项目根执行 -pl -am，构建兄弟模块并 install 到 .m2）"
(cd "$PROJECT" && mvn -q -pl spring-web-benchmark -am -P"$PROFILE" install -DskipTests)

echo "==> [2/3] 生成 WSL 可读 classpath（模块目录内执行）"
# build-classpath 用分号分隔（Windows 惯例，盘符 C: 与分隔符无歧义）
(cd "$BENCH" && mvn -q -P"$PROFILE" dependency:build-classpath \
    -Dmdep.outputFile="$BENCH/target/cp-raw.txt" -Dmdep.pathSeparator=';')

# 路径转换在 WSL 侧执行（Git Bash/MSYS 对 sed 参数有干扰，见 wsl-convert-cp.sh 头注释）。
# MSYS_NO_PATHCONV=1 禁用 Git Bash 的 MSYS 路径转换，否则 /mnt/d/... 参数会被加前缀污染。
CP_RAW_WSL="$(wsl -e wslpath -a "$BENCH/target/cp-raw.txt")"
CP_OUT_WSL="$(wsl -e wslpath -a "$BENCH/target/wsl-cp.txt")"
CONV_WSL="$(wsl -e wslpath -a "$BENCH/scripts/wsl-convert-cp.sh")"
MSYS_NO_PATHCONV=1 wsl -e bash "$CONV_WSL" "$CP_RAW_WSL" "$CP_OUT_WSL"

BENCH_CLASSES_WSL="$(wsl -e wslpath -a "$BENCH/target/classes")"
CP_WSL="$(cat "$BENCH/target/wsl-cp.txt")"

WSL_IP="$(wsl -e hostname -I | awk '{print $1}')"
echo "==> [3/3] 在 WSL 启动服务端 $APP:$PORT (heap=${HEAP_MB}m)"
echo "    连接目标: http://$WSL_IP:$PORT   (JMH 侧传 -Dbenchmark.target=$WSL_IP)"
echo "    Ctrl+C 停止；JFR 落盘 /tmp/${PROFILE#benchmark-}-server.jfr"
echo
wsl -e bash -c "exec java -Xms${HEAP_MB}m -Xmx${HEAP_MB}m -XX:+UseG1GC -XX:+AlwaysPreTouch \
    -XX:FlightRecorderOptions=stackdepth=512 \
    -XX:StartFlightRecording=filename=/tmp/${PROFILE#benchmark-}-server.jfr,settings=profile \
    -cp '${CP_WSL}:${BENCH_CLASSES_WSL}' ${APP} --server.port=${PORT}"

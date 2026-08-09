#!/usr/bin/env bash
# =============================================================================
# WSL external 模式：Windows 侧跑 JMH 压测，客户端连接 WSL 内已启动的服务端。
#
# 用法（Windows Git Bash）:
#   ./scripts/wsl-benchmark.sh [profile] [port] [JMH 参数...]
#     例: ./scripts/wsl-benchmark.sh benchmark-perf 9092
#     例: ./scripts/wsl-benchmark.sh perf 9092 -bm thrpt,sample -rf json -rff target/jmh-results-perf.json
#     例: ./scripts/wsl-benchmark.sh perf 9092 -t 64
#     例: ./scripts/wsl-benchmark.sh perf 9092 -t 64 -rf json -rff out.json
#
# profile 可选: benchmark-perf / benchmark-perf-support / benchmark-tomcat /
#              benchmark-undertow / benchmark-webflux（也接受去掉 benchmark- 前缀的简写）
# 按 profile 自动映射对应 Benchmark 类（PerfBenchmark / PerfSupportBenchmark / ...）
#
# 前置: 已先运行 ./scripts/wsl-server.sh 启动服务端
#
# 说明:
#   - 直连 WSL2 IP（绕开 localhost 中继）
#   - 直接调 org.openjdk.jmh.Main，绕开阿里云镜像缺失的 jmh-maven-plugin:1.37
#   - 用 benchmark 过滤正则限定到本 profile 的类（防 perf-support/undertow 复用
#     src/benchmark-tomcat/java 导致的 TomcatBenchmark 混入）
#   - -Dbenchmark.target / -Dbenchmark.port 必须经 -jvmArgs 传给 fork 子 JVM：
#     JMH fork 的子 JVM 不继承启动 JVM 的系统属性，且命令行 -jvmArgs 覆盖 @Fork 注解
#     jvmArgs，所以堆参数也一并经 -jvmArgs 传（否则 fork 子 JVM 会误判 in-process
#     在 9090 自起服务端）
#   - 客户端 JFR：追加 JMH 参数 -jvmArgsAppend -XX:StartFlightRecording=filename=client.jfr,settings=profile
#   - 结果: 控制台（默认）；追加 -rf json -rff <file> 落盘 JSON
# =============================================================================
set -euo pipefail
# 脚本位于 <project>/spring-web-benchmark/scripts/
cd "$(dirname "$0")/.."
BENCH="$(pwd)"   # <project>/spring-web-benchmark 模块目录

PROFILE="${1:-benchmark-perf}"
PORT="${2:-9092}"
shift 2 || true

# profile → Benchmark 类映射（profile 支持 benchmark-perf / perf 两种写法）
# perf-support/undertow 的 pom 复用了 src/benchmark-tomcat/java，
# 因此 JMH 必须用 benchmark 过滤正则限定到本 profile 的类，防 classpath 污染。
bench_class_of() {
  case "${1#benchmark-}" in
    perf)         echo "PerfBenchmark" ;;
    perf-support) echo "PerfSupportBenchmark" ;;
    tomcat)       echo "TomcatBenchmark" ;;
    undertow)     echo "UndertowBenchmark" ;;
    webflux)      echo "WebFluxBenchmark" ;;
    *) echo "未知 profile: $1（可选: benchmark-perf / benchmark-perf-support / benchmark-tomcat / benchmark-undertow / benchmark-webflux）" >&2; exit 1 ;;
  esac
}
BENCH_CLASS="$(bench_class_of "$PROFILE")"

CP_FILE="$BENCH/target/cp.txt"

if [ ! -f "$CP_FILE" ]; then
    echo "==> 生成 classpath（首次）"
    (cd "$BENCH" && mvn -q -P"$PROFILE" dependency:build-classpath \
        -Dmdep.outputFile="$CP_FILE" -Dmdep.pathSeparator=';')
fi

IP="$(wsl -e hostname -I | awk '{print $1}')"
if [ -z "$IP" ]; then
    echo "错误: 取不到 WSL2 IP。确认 WSL 正在运行（wsl --shutdown 后需先启动过）。"
    exit 1
fi

echo "==> 目标服务端: $IP:$PORT"
echo "==> JMH external 模式（客户端在 Windows，服务端在 WSL）"
echo "==> Benchmark: $BENCH_CLASS (args: $*)"
# 注意: classes 目录必须转成 Windows 路径（cygpath -m），否则 /d/... 与 C:\... 混在
# classpath 里会让原生 JVM 解析失败（报 Unable to find BenchmarkList）。
BENCH_CLASSES_WIN="$(cygpath -m "$BENCH/target/classes")"
CP="$BENCH_CLASSES_WIN;$(cat "$CP_FILE")"
java -Djmh.ignoreLock=true \
    -cp "$CP" org.openjdk.jmh.Main \
    "io.springperf.benchmark.servlet.${BENCH_CLASS}\\..*" \
    -jvmArgs "-Xms1g -Xmx1g -XX:+UseG1GC -XX:+AlwaysPreTouch -Dbenchmark.target=$IP -Dbenchmark.port=$PORT" \
    "$@"

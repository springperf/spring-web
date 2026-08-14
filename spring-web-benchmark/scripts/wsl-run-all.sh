#!/usr/bin/env bash
# =============================================================================
# WSL external 模式一键全量压测 + Markdown 报告。
#
# 服务端跑 WSL2（4c/2g 受限，见 scripts/WSL_SETUP.md），客户端 OkHttp JMH 跑 Windows 宿主，
# 自动遍历全部 profile，跑完生成 benchmark-reports/{run-id}/report.md。
# 睡一觉醒来直接看报告即可。
#
# 用法（Windows Git Bash）:
#   ./scripts/wsl-run-all.sh                          # 全部 5 profile，只吞吐
#   ./scripts/wsl-run-all.sh --sampleTime             # 吞吐+延迟双模式 (~4.5h)
#   ./scripts/wsl-run-all.sh --profiles perf,tomcat   # 只跑指定 profile
#   ./scripts/wsl-run-all.sh --apis json,bytes        # 只跑指定 API（逗号分隔，与老脚本 --apis 一致）
#   ./scripts/wsl-run-all.sh --threads 8              # 指定客户端并发线程（单轮）
#   ./scripts/wsl-run-all.sh --thread-list 1,4,16,64  # 多并发度测试（threads-N 子目录，自动出并发矩阵报告）
#   ./scripts/wsl-run-all.sh --jfr                     # 服务端开 JFR 录制（热点分析用；默认关，
#                                                      #  实测 JFR profile 拖慢服务端 ~38% 吞吐）
#   ./scripts/wsl-run-all.sh -- -w 1 -wi 1 -i 1 -r 1s # 透传 JMH 参数（冒烟用短迭代）
#                                                      # 注意: -- 后所有参数原样透传给每个 profile 的 JMH
#
# 前置条件:
#   - WSL2 已启用且 .wslconfig 限 4c/2g（见 scripts/WSL_SETUP.md）
#   - WSL 内已装 JDK 17（JFR 需要），JAVA_HOME 指向它
#   - Maven / JDK 在 Windows PATH
#   - 端口 9092/9094/9102/9112/9122 空闲（脚本会自动清理 WSL 内残留 java）
#   - 断网挂机支持：所有 Maven 依赖必须已在本地 .m2（先在线构建过一次，
#     mvn -o 离线模式运行，缺依赖会立刻失败而不是卡连阿里云）。
#
# 产物:
#   - benchmark-reports/{run-id}/report.md          主报告（含 latest/report.md 同步）
#   - benchmark-reports/{run-id}/jdk-*/jmh-results-<profile>.json  JMH 原始 JSON
#   - benchmark-reports/{run-id}/<profile>-server.jfr  各服务端 JFR 录音（仅 --jfr 时生成；
#                                                   默认关闭避免拖慢吞吐；落盘 Windows /mnt/d，
#                                                   防 WSL VM 空闲关闭清空 /tmp）
#   - benchmark-reports/{run-id}/<profile>-server.log  各服务端日志
#
# 模式控制（参照 benchmark-all.sh 的 --sampleTime 开关）:
#   - 默认:   -m thrpt              （只吞吐 ≈2.2h）
#   - --sampleTime: -m thrpt,sample （吞吐+延迟双模式 ≈4.5h）
# =============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BENCH="$(cd "$SCRIPT_DIR/.." && pwd)"   # <project>/spring-web-benchmark 模块目录
PROJECT="$(dirname "$BENCH")"           # <project> 项目根（聚合 POM 所在）

RUN_ID=$(date +"%Y%m%d-%H%M%S")
# REPORTS_DIR 固定为绝对路径：修复前是相对路径但部分用法加了 $BENCH/ 前缀（绝对）、
# 部分没加（相对，依赖 cwd）——从模块目录外调用脚本时，JMH 结果写到 cwd 下
# benchmark-reports，而 ReportGenerator/服务端日志读到 $BENCH/benchmark-reports，报告找不到数据。
REPORTS_DIR="$BENCH/benchmark-reports"
CP_DIR="$REPORTS_DIR/.cp"               # classpath 放这，避免被 mvn clean 删除
HEAP_MB=768                             # 服务端堆（配合 WSL 2GB 口径，留余量给 Metaspace/直接内存）
# 强制 Maven 离线（-o）：断网挂机时依赖必须全在本地 .m2，
# 否则 mvn 会卡在连阿里云 http 上重试数分钟。离线模式缺依赖会立刻失败，
# 比挂死更可诊断。运行前请确认已做过一次在线构建把所有依赖拉进本地仓库。
MVN_FLAGS=(-o -q)

# profile:port:benchmark_class:app_class（与 wsl-server.sh / benchmark-all.sh 一致）
DEFAULT_PROFILES=(
  "perf:9092:PerfBenchmark:io.springperf.benchmark.app.PerfApplication"
  "perf-support:9094:PerfSupportBenchmark:io.springperf.benchmark.app.PerfSupportApplication"
  "tomcat:9102:TomcatBenchmark:io.springperf.benchmark.app.TomcatApplication"
  "undertow:9112:UndertowBenchmark:io.springperf.benchmark.app.UndertowApplication"
  "webflux:9122:WebFluxBenchmark:io.springperf.benchmark.app.WebFluxApplication"
)

# ========== 参数解析 ==========
MODE="thrpt"
PROFILES_LIST=()
APIS_LIST=()          # --apis 限定 API（默认空=全部 API）
THREADS=""            # --threads 单轮并发线程（默认用 @Threads(4)）
THREAD_LIST=()        # --thread-list 多并发度
USE_THREAD_SUBDIRS=false
ENABLE_JFR=false      # --jfr 开启服务端 JFR 录制（默认关，实测拖慢服务端 ~38% 吞吐）
JMH_EXTRA=()          # -- 之后原样透传给 JMH 的额外参数
while [[ $# -gt 0 ]]; do
  case "$1" in
    --sampleTime)
      MODE="thrpt,sample"
      shift
      ;;
    --profiles)
      IFS=',' read -ra PROFILES_LIST <<< "$2"
      shift 2
      ;;
    --apis)
      IFS=',' read -ra APIS_LIST <<< "$2"
      shift 2
      ;;
    --threads)
      THREADS="$2"
      shift 2
      ;;
    --thread-list)
      IFS=',' read -ra THREAD_LIST <<< "$2"
      USE_THREAD_SUBDIRS=true
      shift 2
      ;;
    --jfr)
      ENABLE_JFR=true
      shift
      ;;
    --)
      shift
      JMH_EXTRA=("$@")
      break
      ;;
    *)
      echo "用法: $0 [--sampleTime] [--jfr] [--profiles perf,tomcat,...] [--apis a,b,c] [--threads N] [--thread-list 1,4,16] [-- JMH额外参数]"
      exit 1
      ;;
  esac
done

# 确定要跑的 profiles
PROFILES_TO_RUN=()
if [ ${#PROFILES_LIST[@]} -gt 0 ]; then
  for P in "${PROFILES_LIST[@]}"; do
    for ENTRY in "${DEFAULT_PROFILES[@]}"; do
      if [ "${ENTRY%%:*}" = "$P" ]; then
        PROFILES_TO_RUN+=("$ENTRY")
      fi
    done
  done
  if [ ${#PROFILES_TO_RUN[@]} -eq 0 ]; then
    echo "错误: --profiles 中无匹配项（可选: perf,perf-support,tomcat,undertow,webflux）"
    exit 1
  fi
else
  PROFILES_TO_RUN=("${DEFAULT_PROFILES[@]}")
fi

# 确定并发度集合：--thread-list 多轮（threads-N 子目录）；否则单轮（--threads 或 @Threads(4)）
if [ "$USE_THREAD_SUBDIRS" = true ]; then
  THREADS_TO_RUN=("${THREAD_LIST[@]}")
else
  THREADS_TO_RUN=("")
fi

# ========== 前置检查 ==========
echo "=========================================="
echo " Spring WebPerf WSL 全量压测 — $RUN_ID"
echo " 模式: $MODE  |  profiles: ${#PROFILES_TO_RUN[@]}  |  JFR: ${ENABLE_JFR:-off}"
echo "=========================================="

if ! command -v wsl >/dev/null 2>&1; then
  echo "[ERROR] wsl 不可用"; exit 1
fi
if ! command -v curl >/dev/null 2>&1; then
  echo "[ERROR] curl 不可用"; exit 1
fi

WSL_IP="$(wsl -e hostname -I 2>/dev/null | awk '{print $1}')"
if [ -z "$WSL_IP" ]; then
  echo "[ERROR] 取不到 WSL2 IP。确认 WSL 正在运行（wsl --shutdown 后需先启动过）。"
  exit 1
fi
echo "  WSL2 IP: $WSL_IP"

# 防 WSL VM 空闲关闭：profile 编译间隙（纯 Windows mvn 操作）期间 WSL 无进程，
# vmIdleTimeout（默认 60s）会关闭 VM，导致下个 profile 启动服务端时 VM 冷启动 +
# wsl.exe 连接超时（0x8007274c）、java 起不来。方案：整个 run 期间在 WSL 内保持
# 一个持续运行的进程（wrapper 模式，wsl.exe 不退出，VM 有进程活动就不空闲关闭）。
# 用 sleep 36000（10h）覆盖最长 run（--sampleTime ≈4.5h）；EXIT trap 一并 kill。
echo "  [WSL keepalive] 保持 WSL VM 活跃（防编译间隙空闲关闭）..."
WSL_KEEPALIVE_PIDS=()
for KEEP_I in 1 2; do
  wsl -e bash -c "sleep 36000" &
  WSL_KEEPALIVE_PIDS+=("$!")
done
echo "  WSL keepalive PIDs: ${WSL_KEEPALIVE_PIDS[*]}"

# EXIT trap：从 keepalive 进程启动后【立即】安装，覆盖 Step 1 全量编译阶段——
# 否则 mvn install 失败（exit 1）或用户 Ctrl+C 时，已启动的 keepalive wsl 进程与
# no-sleep.ps1 全部泄漏（no-sleep 使 Windows 持续不睡眠）。trap 在 EXIT 时求值，
# 此时 NOSLEEP_PID / WRAPPERS 均已定义，${VAR:-} 防御 set -u 对未定义变量的报错。
WRAPPERS=()   # 已启动的服务端 wrapper (wsl.exe) PID；EXIT trap 兜底清理
trap 'for w in ${WRAPPERS[@]+"${WRAPPERS[@]}"}; do kill -9 "$w" 2>/dev/null; done; for k in ${WSL_KEEPALIVE_PIDS[@]+"${WSL_KEEPALIVE_PIDS[@]}"}; do kill -9 "$k" 2>/dev/null; done; [ -n "${NOSLEEP_PID:-}" ] && kill "$NOSLEEP_PID" 2>/dev/null || true' EXIT

JDK_VERSION="$(java -version 2>&1 | head -1 | cut -d'"' -f2)"
JDK_DIR="jdk-${JDK_VERSION}"
RESULTS_DIR="$REPORTS_DIR/$RUN_ID/$JDK_DIR"
mkdir -p "$RESULTS_DIR" "$CP_DIR" "$REPORTS_DIR/$RUN_ID"
echo "  结果目录: $RESULTS_DIR"

# 运行元信息：ReportGenerator 读它显示在报告头部（mode/threads/profiles/apis）。
# 放在 run 根目录，兼容单线程与 --thread-list 的 threads-N 目录结构。
if [ "$USE_THREAD_SUBDIRS" = true ]; then
  THREADS_STR="$(IFS=,; echo "${THREAD_LIST[*]}")"
else
  THREADS_STR="${THREADS:-4}"
fi
if [ ${#APIS_LIST[@]} -gt 0 ]; then
  APIS_STR="$(IFS=,; echo "${APIS_LIST[*]}")"
else
  APIS_STR="all"
fi
PROFILE_NAMES=()
for E in "${PROFILES_TO_RUN[@]}"; do PROFILE_NAMES+=("${E%%:*}"); done
PROFILES_STR="$(IFS=,; echo "${PROFILE_NAMES[*]}")"
if [ "$ENABLE_JFR" = true ]; then JFR_STR=on; else JFR_STR=off; fi
cat > "$REPORTS_DIR/$RUN_ID/run-meta.txt" <<EOF
mode=$MODE
threads=$THREADS_STR
profiles=$PROFILES_STR
apis=$APIS_STR
jfr=$JFR_STR
EOF
echo "  运行元信息: mode=$MODE, threads=$THREADS_STR, profiles=$PROFILES_STR, apis=$APIS_STR"

# 防止断网挂机时 Windows 睡眠中断压测。
# 方案：后台拉起 scripts/no-sleep.ps1（SetThreadExecutionState 防睡眠，无需管理员，
# 进程存活期间系统不睡眠）。压测结束（或 Ctrl+C）由 EXIT trap kill 掉它。
# 不用 powercfg /change——非管理员 Windows 10 Home 会被拒绝。
NOSLEEP_PID=""
if [ -f "$SCRIPT_DIR/no-sleep.ps1" ]; then
  powershell -NoProfile -ExecutionPolicy Bypass -File "$SCRIPT_DIR/no-sleep.ps1" &
  NOSLEEP_PID=$!
  echo "  [防睡眠] 已启动 no-sleep.ps1 (PID $NOSLEEP_PID)，压测期间 Windows 不睡眠"
else
  echo "  [WARN] 未找到 scripts/no-sleep.ps1，压测期间若 Windows 睡眠将中断运行"
fi

# ========== Step 1: 全量编译 ==========
echo ""
echo "[1/4] 全量编译（项目根执行，安装兄弟模块到 .m2）..."
(cd "$PROJECT" && mvn "${MVN_FLAGS[@]}" -pl spring-web-benchmark -am install -DskipTests)
if [ $? -ne 0 ]; then
  echo "[ERROR] 编译失败"; exit 1
fi

# ========== Step 2+3: 逐 profile 构建 + 压测 ==========
echo ""
echo "[2/3] 逐 profile 编译 + WSL 服务端 + Windows 压测..."
FAILED_PROFILES=()

for ENTRY in "${PROFILES_TO_RUN[@]}"; do
  IFS=':' read -r P PORT BENCH_CLASS APP_CLASS <<< "$ENTRY"
  echo ""
  echo "  === Profile: $P (port $PORT, $BENCH_CLASS) ==="

  # 2a. 编译当前 profile（clean 防污染：perf-support/undertow 复用 tomcat source）
  echo "    [compile] mvn -P benchmark-$P clean compile ..."
  (cd "$BENCH" && mvn "${MVN_FLAGS[@]}" -P"benchmark-$P" clean compile)
  if [ $? -ne 0 ]; then
    echo "    -> COMPILE FAIL, skipping $P"
    FAILED_PROFILES+=("$P")
    continue
  fi

  # 2b. 生成 Windows classpath（放 .cp 避免被 clean 删除）
  echo "    [classpath] dependency:build-classpath ..."
  (cd "$BENCH" && mvn "${MVN_FLAGS[@]}" -P"benchmark-$P" dependency:build-classpath \
      -Dmdep.outputFile="$CP_DIR/cp-$P-raw.txt" -Dmdep.pathSeparator=';')
  if [ $? -ne 0 ] || [ ! -s "$CP_DIR/cp-$P-raw.txt" ]; then
    echo "    -> CLASSPATH FAIL, skipping $P"
    FAILED_PROFILES+=("$P")
    continue
  fi

  # 2c. 转换 WSL 侧 classpath（Git Bash 对 sed 有 MSYS 干扰，转换在 WSL 内做）
  CP_RAW_WSL="$(wsl -e wslpath -a "$CP_DIR/cp-$P-raw.txt")"
  CP_OUT_WSL="$(wsl -e wslpath -a "$CP_DIR/wsl-cp-$P.txt")"
  CONV_WSL="$(wsl -e wslpath -a "$BENCH/scripts/wsl-convert-cp.sh")"
  MSYS_NO_PATHCONV=1 wsl -e bash "$CONV_WSL" "$CP_RAW_WSL" "$CP_OUT_WSL"
  CLASSES_WSL="$(wsl -e wslpath -a "$BENCH/target/classes")"
  CP_WSL="$(cat "$CP_DIR/wsl-cp-$P.txt")"

  # 2e. WSL 后台启动服务端 + 就绪探测（封装为函数，失败自动重试一次）。
  # 关键1：WSL2 的 wsl.exe 调用一退出就会清理其后代进程（nohup/setsid 都无法脱离），
  # 因此必须让 wsl.exe 保持存活：在 Windows 侧后台跑一个 wrapper（内部 "... ; wait"），
  # java 作为其子进程，直到压测结束被 SIGTERM 后 wait 返回、wrapper 自动退出。
  # 关键2：JFR/日志不能写 /tmp（tmpfs）——压测结束 WSL VM 空闲自动关闭会清空 /tmp。
  # 落盘到 /mnt/d（Windows 磁盘），VM 重启也不丢。
  # 关键3：VM 若在 profile 间隙被关闭（冷启动），首次启动可能失败；就绪探测失败后
  # kill 残留 + 重启服务端再探测一次，二次失败才跳过该 profile 记 FAIL。
  start_server() {
    # 清理 WSL 内残留同端口 java 进程（防上次 run 残留）
    wsl -e bash -c "ps aux | grep -E 'server.port=${PORT}\$' | grep -v grep | awk '{print \$2}' | xargs -r kill -9" 2>/dev/null
    sleep 1
    LOG_WSL="$(wsl -e wslpath -a "$REPORTS_DIR/$RUN_ID/$P-server.log")"
    PID_WSL="/tmp/${P}-server.pid"   # pid 文件小、瞬时使用，tmpfs 可接受
    # JFR 可选：默认关闭（实测 profile 模式拖慢服务端 ~38% 吞吐，TPS 数据不受影响——
    # 吞吐来自 JMH 客户端结果 JSON）。--jfr 开启供热点分析。
    JFR_OPTS=""
    if [ "$ENABLE_JFR" = true ]; then
      JFR_WSL="$(wsl -e wslpath -a "$REPORTS_DIR/$RUN_ID/$P-server.jfr")"
      JFR_OPTS="-XX:FlightRecorderOptions=stackdepth=512 -XX:StartFlightRecording=filename=${JFR_WSL},settings=profile"
    fi
    echo "    [server] 启动 $APP_CLASS:$PORT in WSL (attempt $1, JFR=$([ "$ENABLE_JFR" = true ] && echo on || echo off)) ..."
    wsl -e bash -c "nohup java -Xms${HEAP_MB}m -Xmx${HEAP_MB}m -XX:+UseG1GC -XX:+AlwaysPreTouch ${JFR_OPTS} -cp '${CP_WSL}:${CLASSES_WSL}' ${APP_CLASS} --server.port=${PORT} > ${LOG_WSL} 2>&1 & echo \$! > ${PID_WSL}; wait" &
    SERVER_WRAPPER_PID=$!
    sleep 3
    WRAPPERS+=("$SERVER_WRAPPER_PID")
    SERVER_PID="$(wsl -e bash -c "cat ${PID_WSL} 2>/dev/null")"
    echo "    server wrapper PID: $SERVER_WRAPPER_PID, java PID: ${SERVER_PID:-unknown}"
    # 就绪探测：curl 直到 200 或 90s 超时
    READY=false
    for i in $(seq 1 45); do
      CODE="$(curl -s -m 5 -o /dev/null -w "%{http_code}" "http://$WSL_IP:$PORT/api/demo/hello/probe/aaaxxx?p1=1&p2=2&p3=3&p4=4&p5=5" 2>/dev/null)"
      if [ "$CODE" = "200" ]; then
        READY=true
        break
      fi
      sleep 2
    done
  }

  start_server 1
  if [ "$READY" != "true" ]; then
    echo "    -> 第 1 次启动未就绪，清理后重试..."
    [ -n "${SERVER_PID:-}" ] && wsl -e bash -c "kill -9 ${SERVER_PID} 2>/dev/null" || true
    [ -n "${SERVER_WRAPPER_PID:-}" ] && kill -9 "$SERVER_WRAPPER_PID" 2>/dev/null || true
    sleep 3
    start_server 2
  fi
  if [ "$READY" != "true" ]; then
    echo "    -> SERVER NOT READY (2 attempts), killing and skipping $P"
    [ -n "${SERVER_PID:-}" ] && wsl -e bash -c "kill -9 ${SERVER_PID} 2>/dev/null" || true
    [ -n "${SERVER_WRAPPER_PID:-}" ] && kill -9 "$SERVER_WRAPPER_PID" 2>/dev/null || true
    FAILED_PROFILES+=("$P")
    continue
  fi
  echo "    server ready in ~$((i*2))s"

  # 2g. Windows 跑 JMH（客户端，直连 WSL IP）。
  # 关键：JMH fork 的子 JVM 不继承启动 JVM 的系统属性，且命令行 -jvmArgs 会
  # 覆盖 @Fork 注解的 jvmArgs。因此堆参数 + benchmark.target/port 必须显式经
  # -jvmArgs 一并传给 fork 子 JVM，否则子 JVM 会误判 in-process 在 9090 自起服务端。
  # --apis：include 正则限定到指定 API；默认 .* 跑全部（7 个 API）。
  INCLUDE_ARG="io.springperf.benchmark.servlet.${BENCH_CLASS}\\..*"
  if [ ${#APIS_LIST[@]} -gt 0 ]; then
    INCLUDE_ARG="io.springperf.benchmark.servlet.${BENCH_CLASS}\\.($(IFS='|'; echo "${APIS_LIST[*]}"))$"
  fi
  echo "    [benchmark] JMH $BENCH_CLASS (mode=$MODE) ..."
  BENCH_CLASSES_WIN="$(cygpath -m "$BENCH/target/classes")"
  CP_WIN_FILE="$CP_DIR/cp-$P-raw.txt"
  CP_WIN="$BENCH_CLASSES_WIN;$(cat "$CP_WIN_FILE")"
  # --thread-list 多并发度：每轮 -t N，结果写 threads-N/jdk-*/ 子目录
  # （ReportGenerator 检测到 threads-N 结构自动生成并发伸缩性矩阵报告）。
  # 否则单轮：-t 取 --threads（默认 @Threads(4)），结果写 jdk-*/ 目录。
  # 服务端只启动一次，各并发度复用同一实例。
  for T in "${THREADS_TO_RUN[@]}"; do
    if [ -z "$T" ]; then
      T_DIR=""
      T_ARG=()
      [ -n "$THREADS" ] && T_ARG=(-t "$THREADS")
      LABEL="threads=${THREADS:-4}"
    else
      T_DIR="threads-${T}"
      T_ARG=(-t "$T")
      LABEL="threads=$T"
    fi
    RESULTS_DIR="$REPORTS_DIR/$RUN_ID${T_DIR:+/$T_DIR}/$JDK_DIR"
    mkdir -p "$RESULTS_DIR"
    echo "    [benchmark] $LABEL -> $RESULTS_DIR/jmh-results-$P.json"
    java -Djmh.ignoreLock=true \
        -cp "$CP_WIN" org.openjdk.jmh.Main \
        "$INCLUDE_ARG" \
        -bm "$MODE" -rf json -rff "$RESULTS_DIR/jmh-results-$P.json" \
        -jvmArgs "-Xms1g -Xmx1g -XX:+UseG1GC -XX:+AlwaysPreTouch -Dbenchmark.target=$WSL_IP -Dbenchmark.port=$PORT" \
        -prof gc "${T_ARG[@]}" "${JMH_EXTRA[@]+"${JMH_EXTRA[@]}"}"
    if [ $? -ne 0 ]; then
      echo "    -> BENCHMARK FAIL for $P ($LABEL)"
      FAILED_PROFILES+=("$P")
    else
      echo "    -> SUCCESS: $RESULTS_DIR/jmh-results-$P.json"
    fi
  done

  # 2h. 优雅停止服务端（SIGTERM→Spring Boot 关闭→JFR 落盘）。
  # SIGTERM java → wrapper 内 wait 返回 → wrapper(wsl.exe) 自动退出。
  # 检测 wrapper 进程是否已退出；15s 未退出则 kill -9 java + 强杀 wrapper。
  if [ -n "${SERVER_PID:-}" ]; then
    echo "    [server] 停止 (SIGTERM) ..."
    wsl -e bash -c "kill ${SERVER_PID} 2>/dev/null"
    for i in $(seq 1 15); do
      if ! kill -0 "$SERVER_WRAPPER_PID" 2>/dev/null; then
        break
      fi
      sleep 1
    done
    if kill -0 "$SERVER_WRAPPER_PID" 2>/dev/null; then
      wsl -e bash -c "kill -9 ${SERVER_PID} 2>/dev/null" || true
      kill -9 "$SERVER_WRAPPER_PID" 2>/dev/null || true
    fi
  fi
done

# ========== Step 3.5: JFR 落盘检查（仅 --jfr 开启时）==========
# 0 字节 JFR = 服务端未优雅关闭（VM 关闭/强杀），热点分析数据缺失，显式告警。
if [ "$ENABLE_JFR" = true ]; then
  ZERO_JFR=()
  for f in "$REPORTS_DIR/$RUN_ID"/*.jfr; do
    [ -f "$f" ] && [ ! -s "$f" ] && ZERO_JFR+=("$(basename "$f")")
  done
  if [ ${#ZERO_JFR[@]} -gt 0 ]; then
    echo ""
    echo "  [WARN] JFR 未落盘（0 字节，服务端未优雅关闭）: ${ZERO_JFR[*]}"
  fi
fi

# ========== Step 4: 生成报告 ==========
echo ""
echo "[3/3] 生成报告..."
# ReportGenerator 用默认 profile classpath（含 jackson）
(cd "$BENCH" && mvn "${MVN_FLAGS[@]}" dependency:build-classpath \
    -Dmdep.outputFile="$CP_DIR/cp-report.txt" -Dmdep.pathSeparator=';')
if [ -s "$CP_DIR/cp-report.txt" ]; then
  CP_REPORT="$(cygpath -m "$BENCH/target/classes");$(cat "$CP_DIR/cp-report.txt")"
  java -cp "$CP_REPORT" \
    io.springperf.benchmark.report.generator.ReportGenerator "$(cygpath -m "$REPORTS_DIR")"
else
  echo "[WARN] 报告 classpath 缺失，跳过报告生成"
fi

echo ""
echo "=========================================="
echo " 完成!"
echo " 报告: $REPORTS_DIR/$RUN_ID/report.md"
echo " 最新: $REPORTS_DIR/latest/report.md"
if [ ${#FAILED_PROFILES[@]} -gt 0 ]; then
  echo " 失败 profile: ${FAILED_PROFILES[*]}"
else
  echo " 全部 profile 成功"
fi
echo "=========================================="

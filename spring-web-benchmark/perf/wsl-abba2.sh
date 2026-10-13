#!/usr/bin/env bash
# WSL 分离模式吞吐 ABBA —— **唯一可信的吞吐对比口径**
#
# 为什么必须用这个：in-process 模式（客户端与服务端同 JVM）在 4 核机器上
# 噪声 sd 达10~20%，而版本差异仅约 3~10% —— 噪声与信号同阶，无法得出结论。
# 拆到不同进程后 sd 降到 5.3%，吞吐绝对值提升 2.4 倍。
#
# 用法: wsl-abba2.sh [本次轮数]
#   追加模式：可分多次运行累积轮次（脚本不截断已有数据）
#     ./wsl-abba2.sh 6&& ./wsl-abba2.sh 24   # 累计 30 轮
# 30 轮约 78 分钟。**仅在发版前跑**，不适合做每次提交的 CI 门禁。
#
# 前置：
#   1. WSL 内已装 JDK 17（scripts/WSL_SETUP.md）
#   2. %UserProfile%\.wslconfig 配置为 4c/2g（scripts/WSL_SETUP.md）
#   3. 基线 worktree：git worktree add <path> <commit>，并 export BASELINE_WT=<path>
#      不设则跳过基线组，只测当前版
#
# 判定标准（三条缺一不可，见方法论 §2.4）：
#   1. 配对 t 检验：t > 2.042（n=30）
#   2. 符号检验：p< 0.05（30 轮需胜出 >= 22）
#   3. **分段稳定性**：按 6 轮分段，各段差值均值不应反向摆动
#
# 方法论见 docs/feature/performance-analysis-methodology.md §2.3
set -uo pipefail
source "$(dirname "$0")/perf-env.sh"

ROUNDS="${1:-6}"
PORT="${2:-9092}"
SRV="$PERF_DIR/wsl-perf-server.sh"
# wsl.exe 的路径规则（三种形式实测）：
#   /d/x         → 原样传给 Linux bash，但 WSL 只挂载 /mnt/d，Linux 侧不存在 → 失败
#   D:/x         → 同上
#   /mnt/d/x     → ✅
# 两个必要条件：① 用 /mnt/d 形式；② 设 MSYS_NO_PATHCONV=1，
# 否则 wsl.exe 会把 /mnt/d 当相对路径 prepend 到 Git Bash 根目录，变成
# 「C:/Program Files/Git/mnt/d...」（实测踩过）。
# 注意 `${1#/}` 去掉的是斜杠而非盘符 —— PERF_DIR 形如 /d/workspace/...。
wsl_path() { echo "/mnt/${1#/}"; }
wsl_srv() { MSYS_NO_PATHCONV=1 wsl -d Ubuntu -- bash "$(wsl_path "$SRV")" "$@"; }
P=16000

if [ -z "$BASELINE_WT" ]; then
  echo "WARN: 未设 BASELINE_WT，跳过基线组（仅测当前版 sd）。" >&2
  echo "      基线创建: git worktree add <path> <commit>  然后 export BASELINE_WT=<path>" >&2
fi

WSL_IP=$(wsl -d Ubuntu -- hostname -I 2>/dev/null | tr -d '\0' | awk '{print $1}')
[ -n "$WSL_IP" ] || { echo "ERROR: 取不到 WSL IP" >&2; exit 1; }
echo "### WSL 分离模式 ABBA x$ROUNDS  server=WSL $WSL_IP:$PORT"

mkdir -p "$PERF_OUT"
OUT_CUR="$PERF_OUT/wsl-abba-cur.txt"
OUT_BASE="$PERF_OUT/wsl-abba-base.txt"
# Windows 侧 python 读不到 Git Bash 路径（/d/...），需另给一份
OUT_CUR_WIN="$PERF_OUT_WIN/wsl-abba-cur.txt"
OUT_BASE_WIN="$PERF_OUT_WIN/wsl-abba-base.txt"
[ -f "$OUT_CUR" ] || : > "$OUT_CUR"
[ -f "$OUT_BASE" ] || : > "$OUT_BASE"
echo "  已有 $(wc -l < "$OUT_CUR") 轮，本次新增 $ROUNDS 轮"

perf_clear_jmh_lock

# 两组各自的服务端：基线用其 worktree 内的 benchmark classes，
# 否则 classpath 会指到当前版的 benchmark（污染对照）
CP_CUR="${WIN_REPO}/spring-web-benchmark/target/classes"
CP_CUR="$(perf_module_classpath);$CP_CUR;$(cat "$BENCH_DIR/target/cp-perf.txt");$SERVLET_JAR"
if [ -n "$BASELINE_WT" ]; then
  BW="$(cd "$BASELINE_WT" && pwd -W)"
  CP_BASE=""
  for m in $PERF_MODULES; do CP_BASE="${CP_BASE}${CP_BASE:+;}$BW/$m/target/classes"; done
  # classpath 文件必须取基线 worktree 自己的：当前仓库的 cp-perf.txt 里
  # 依赖版本可能与基线不同，混用会让对照组不纯。
  CP_BASE="${CP_BASE};$BW/spring-web-benchmark/target/classes;$(cat "$BW/spring-web-benchmark/target/cp-base.txt");$SERVLET_JAR"
fi

srv_stop() { wsl_srv stop >/dev/null 2>&1; }
# 启动失败必须可见：静默失败会让客户端连不上、结果全是NA，
# 而排查时只能看到「NA」无从知道是服务端没起来。
# 判据用 wsl-perf-server.sh 打印的「服务端已就绪」（它内部靠端口探测判定）。
srv_start() {
  local out
  out="$(wsl_srv start "$1" "$PORT" 768 2>&1 | tr -d '\0')"
  if echo "$out" | grep -q "服务端已就绪"; then
    return 0
  fi
  echo "    [srv_start 失败] $(echo "$out" | grep -aE 'ERROR|已停止|无运行' | head -2 | tr '\n' ' ')" >&2
  return 1
}

client() { # $1=classpath $2=端点名 -> "端点 ops/s"
  P=$((P+1))
  local ep="${2:-json}"
  # 注意：必须给 v 赋初值。set -u 下「声明但未赋值」在部分 bash 版本
  # 会在命令替换为空时被当成 unbound variable（曾导致 line 97报错）。
  local v=""
  # 原始输出落盘：解析失败时要能看出是「连不上」还是「格式变了」，
  # 只留grep 后的行会丢失JMH 启动阶段的异常栈。
  local raw="$PERF_OUT/raw-$ep-$P.txt"
  # 多迭代时 JMH 会输出多行同格式结果，且行尾可能带 \r。
  #正则只要求「PerfBenchmark.<ep>」与「thrpt」同行，数值取该行第 4 个字段
  # （格式：<name> thrpt <cnt> <score> <error> <unit>）。
  ( cd "$BENCH_DIR" && "$JDK17/bin/java" -cp "$1" org.openjdk.jmh.Main "PerfBenchmark.$ep" \
      -f 1 -wi 3 -i 4 -r 4s -w 3s \
      -jvmArgsAppend "-Dbenchmark.target=$WSL_IP -Dbenchmark.port=$PORT" ) > "$raw" 2>&1
  v=$( tr '\r' '\n' < "$raw" \
    | grep -aE "PerfBenchmark\.${ep}[[:space:]]+thrpt" \
    | awk '{print $4}' \
    | tail -1 )
  if [ -z "$v" ]; then
    # 客户端没拿到数据：打印原始输出的关键行，区分「连不上」与「格式变化」
    echo "    [client 失败 ep=$ep] $(grep -aE 'Exception|Caused|Connect|thrpt|Error|Benchmark ' "$raw" | head -3 | tr '\n' ' ')" >&2
    echo "    [原始输出] $raw" >&2
    v="NA"
  fi
  echo "$ep $v"
}

# 端点清单：json 是唯一带 request body + ResponseEntity 的路径，
# 其余端点的分配结构差异很大（get 走查询串解析、bytes 走 chunked 直写）。
# 逐端点独立计数，不混在一个 median里。
EPISODES="${EPISODES:-json get bytes}"

for r in $(seq 1 "$ROUNDS"); do
  for ep in $EPISODES; do
    if [ -n "$BASELINE_WT" ]; then
      srv_stop
      # 启动失败则跳过本端点：记 NA 会污染统计（NA 被 python 过滤，
      # 但会掩盖「服务端没起来」这个事实），且白白浪费后续轮次
      if srv_start "$BASELINE_WT"; then
        b=$(client "$CP_BASE" "$ep"); echo "  r$r  BASE $b"; echo "$b" >> "$OUT_BASE"
      else
        echo "  r$r  BASE $ep 跳过（服务端未就绪）"
      fi
    fi
    srv_stop
    if srv_start "$REPO_ROOT"; then
      c=$(client "$CP_CUR" "$ep"); echo "  r$r  CUR  $c"; echo "$c" >> "$OUT_CUR"
    else
      echo "  r$r  CUR  $ep 跳过（服务端未就绪）"
    fi
  done
done
srv_stop

"$PYEXE" - "$OUT_BASE_WIN" "$OUT_CUR_WIN" <<'PY'
import statistics as s, sys
from math import comb
from collections import defaultdict

def load(p):
    """读 '端点 ops/s' 格式 -> {端点: [ops/s]}"""
    out = defaultdict(list)
    for line in open(p):
        parts = line.split()
        if len(parts) == 2 and parts[0] != '端点':
            try: out[parts[0]].append(float(parts[1]))
            except ValueError: pass
    return out

b, c = load(sys.argv[1]), load(sys.argv[2])
if not b or not c:
    print('数据不足（或仅有 NA）'); raise SystemExit
eps = [e for e in c if e in b and len(c[e]) >= 2 and len(b[e]) == len(c[e])]

for e in eps:
    bb, cc = b[e], c[e]
    n = len(bb)
    d = [y - x for x, y in zip(bb, cc)]
    md_, sd_d = s.mean(d), (s.stdev(d) if n > 1 else 0)
    t = md_ / (sd_d / n**0.5) if sd_d else 0
    wins = sum(1 for x in d if x > 0)
    p = sum(comb(n, k) for k in range(max(wins, n - wins), n + 1)) / 2**n
    mb, mc = s.mean(bb), s.mean(cc)
    sdb, sdc = s.stdev(bb), s.stdev(cc)
    print(f'\n=== 端点 {e}（n={n}）')
    print(f'  BASE mean={mb:9.1f} sd={sdb:7.1f}({sdb/mb*100:4.1f}%)')
    print(f'  CUR  mean={mc:9.1f} sd={sdc:7.1f}({sdc/mc*100:4.1f}%)')
    print(f'  CUR-BASE = {md_:+.1f}ops/s  相对 {(mc/mb-1)*100:+.2f}%  '
          f't={t:.2f}  胜出 {wins}/{n} p={p:.4f} -> {"显著" if p < 0.05 else "不显著"}')
    # 分段稳定性（第3 条判定）
    signs = {1 if s.mean(d[k:k+6]) > 0 else -1 for k in range(0, n, 6)}
    print(f'  分段稳定性: {"稳定" if len(signs) == 1 else "⚠️ 反向摆动，效应量不可信"}')
    if len(signs) > 1:
        for k in range(0, n, 6):
            seg = d[k:k+6]
            print(f'    r{k+1:>2}-{k+len(seg):<2} {s.mean(seg):+8.1f}  胜出 {sum(1 for x in seg if x>0)}/{len(seg)}')
PY
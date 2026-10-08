#!/usr/bin/env bash
# 睡前挂机：全端点 × 两版本 的分配量 + 吞吐对比
#
# 填补两个数据缺口：
#   1. 之前的 30轮 ABBA 只跑 json 一个端点，框架实际有 7 个benchmark 方法
#   2. 分配量报告曾因@TearDown 覆盖，只剩最后一个端点（已修）
#
# 无人值守要求：任何一步失败都要记录但**继续跑完**，不中断整夜任务。
#
# 用法: overnight.sh [阶段...]
#   不带参数 = 全部阶段
#   阶段: alloc | thrpt | noise
set -uo pipefail
source "$(dirname "$0")/perf-env.sh"

STAGES="${*:-alloc thrpt noise}"
LOG="$PERF_OUT/overnight-$(date +%Y%m%d-%H%M%S).log"
mkdir -p "$PERF_OUT"
PORT=17000
FAILED=0

log() { echo "[$(date '+%H:%M:%S')] $*" | tee -a "$LOG"; }
stage() { case " $STAGES " in *" $1 "*) return 0;; *) return 1;; esac; }

cd "$BENCH_DIR" || exit 1
log "===== 挂机任务开始（阶段: $STAGES）"
log "报告目录: $PERF_OUT"
log "日志: $LOG"
[ -n "$BASELINE_WT" ] && log "基线 worktree: $BASELINE_WT" \
                      || log "未设 BASELINE_WT —— 吞吐阶段只测当前版"

# ============ 阶段 1：分配量（快，~1.5 min/次）============
# 精度最高，优先跑完。即便后续阶段失败，这部分结果也可用。
if stage alloc; then
  log "--- 阶段1: 服务端分配量（当前版，全端点）"
  PORT=$((PORT+1))
  if bash "$PERF_DIR/measure-alloc.sh" cur-all "$PORT" >>"$LOG" 2>&1; then
    log "  ✓ 当前版分配量完成"
  else
    log "  ✗ 当前版失败（继续）"; FAILED=$((FAILED+1))
  fi

  if [ -n "$BASELINE_WT" ]; then
    log "--- 阶段1: 服务端分配量（基线版）"
    PORT=$((PORT+1))
    # 第三个参数显式传基线路径 —— 必须用基线自己的 target/classes 与 classpath
    if bash "$PERF_DIR/measure-alloc.sh" base-all "$PORT" "$BASELINE_WT" >>"$LOG" 2>&1; then
      log "  ✓ 基线版分配量完成"
    else
      log "  ✗ 基线版失败（继续）"; FAILED=$((FAILED+1))
    fi

    # ---- 自检：两次测量若几乎相同，说明有测量污染 ----
    # 实测过一次异常：cur 与 base 的 bytes 同为 2828、json 仅差 14 —— 两份不同代码
    # 不可能给出逐字节相同的分配量。该异常态会静默产出错误的「无差异」结论，
    # 比报错更危险，故在此强制拦截。
    CJSON="$PERF_OUT/server-alloc-cur-all.json"
    BJSON="$PERF_OUT/server-alloc-base-all.json"
    if [ -f "$CJSON" ] && [ -f "$BJSON" ]; then
      SUSPECT=$("$PYEXE" - "$CJSON" "$BJSON" <<'PY'
import json, sys
try:
    c = json.load(open(sys.argv[1])); b = json.load(open(sys.argv[2]))
except Exception:
    print(""); raise SystemExit
flags = []
for k in ('json', 'get', 'bytes'):
    ck, bk = c.get(k + '.median'), b.get(k + '.median')
    if ck and bk and abs(ck - bk) / bk < 0.01:
        flags.append(f'{k}:{ck}/{bk}')
print(' '.join(flags))
PY
)
      if [ -n "$SUSPECT" ]; then
        log "  ⚠️ 自检失败：cur 与 base 差异 <1%（$SUSPECT）"
        log "     两份不同代码不应几乎相同，本次分配量结论不可信，请重跑。"
        FAILED=$((FAILED+1))
      else
        log "  ✓ 自检通过：cur 与 base 存在预期差异"
      fi
    fi
  fi
fi

# ============ 阶段 2：吞吐 ABBA（慢，~2.6 min/轮）============
# 唯一可信口径。追加模式：先跑若干轮，明早可看部分结果。
if stage thrpt; then
  if [ -n "$BASELINE_WT" ]; then
    log "--- 阶段2: WSL 分离吞吐 ABBA（BASE vs CUR，每轮约 2.6 min）"
    if bash "$PERF_DIR/wsl-abba2.sh" "${OVERNIGHT_ROUNDS:-18}" >>"$LOG" 2>&1; then
      log "  ✓ 吞吐ABBA 完成"
    else
      log "  ✗ 吞吐 ABBA 失败（继续）"; FAILED=$((FAILED+1))
    fi
  else
    log "--- 阶段2: 跳过（无基线，无法 A/B）"
  fi
fi

# ============ 阶段 3：口径噪声（用于写进结论）============
if stage noise; then
  log "--- 阶段3: 固定 1g 堆的 sd（6 轮）"
  if bash "$PERF_DIR/heap-noise.sh" 1g 6 >>"$LOG" 2>&1; then
    log "  ✓ sd 测量完成"
  else
    log "  ✗ sd 测量失败（继续）"; FAILED=$((FAILED+1))
  fi
fi

log "===== 挂机任务结束（失败 $FAILED 项）"
log "结果文件:"
for f in server-alloc-cur-all.json server-alloc-base-all.json \
         wsl-abba-cur.txt wsl-abba-base.txt heap-1g.txt; do
  [ -f "$PERF_OUT/$f" ] && log "  $PERF_OUT/$f ($(wc -l < "$PERF_OUT/$f") 行)"
done
log "完整日志: $LOG"
exit $((FAILED > 0 ? 1 : 0))

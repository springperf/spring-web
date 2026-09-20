#!/bin/bash
#
# check-jfr-truncation.sh — 校验 JFR 产物是否存在「栈截断」
#
# 用法:
#   ./check-jfr-truncation.sh <jfr 文件或目录> [...]
#
# 背景:
#   JVM 默认 JFR 栈深为 64 帧。若录制时只给了 -XX:StartFlightRecording 而漏配
#   -XX:FlightRecorderOptions=stackdepth=N，深栈样本会在 JFR 中被标记 truncated：
#   被砍掉的是【外层】帧（Netty / 框架入口 → 调用链根部），导致火焰图与热点归因失真。
#   注意这与 `jfr print --stack-depth` 无关——那个只控制【打印】层，不改录制数据。
#
# 退出码: 0 = 无截断；1 = 存在截断；2 = 用法/环境错误
#
set -uo pipefail

if [ $# -eq 0 ]; then
  echo "用法: $0 <jfr 文件或目录> [...]" >&2
  exit 2
fi

JFR_BIN="${JFR_BIN:-jfr}"
if ! command -v "$JFR_BIN" >/dev/null 2>&1; then
  echo "错误: 找不到 jfr 命令（可用 JFR_BIN 指向 \$JAVA_HOME/bin/jfr）" >&2
  exit 2
fi

FILES=()
for target in "$@"; do
  if [ -d "$target" ]; then
    while IFS= read -r f; do FILES+=("$f"); done < <(find "$target" -maxdepth 1 -name '*.jfr' | sort)
  elif [ -f "$target" ]; then
    FILES+=("$target")
  else
    echo "跳过（不存在）: $target" >&2
  fi
done

if [ ${#FILES[@]} -eq 0 ]; then
  echo "错误: 没有可检查的 .jfr 文件" >&2
  exit 2
fi

TRUNCATED_FILES=0
for f in "${FILES[@]}"; do
  JSON="$("$JFR_BIN" print --events jdk.ExecutionSample --json --stack-depth 2048 "$f" 2>/dev/null)"
  TOTAL="$(printf '%s\n' "$JSON" | grep -c '"truncated":' || true)"
  BAD="$(printf '%s\n' "$JSON" | grep -c '"truncated": true' || true)"
  if [ "$TOTAL" -eq 0 ]; then
    printf '%-46s 无 ExecutionSample 事件\n' "$(basename "$f")"
    continue
  fi
  RATE="$(awk -v b="$BAD" -v t="$TOTAL" 'BEGIN{printf "%.1f", 100*b/t}')"
  if [ "$BAD" -gt 0 ]; then
    printf '%-46s samples=%-6s truncated=%-6s (%s%%) <= 存在栈截断\n' \
      "$(basename "$f")" "$TOTAL" "$BAD" "$RATE"
    TRUNCATED_FILES=$((TRUNCATED_FILES + 1))
  else
    printf '%-46s samples=%-6s truncated=0 (%s%%) OK\n' "$(basename "$f")" "$TOTAL" "$RATE"
  fi
done

echo ""
if [ "$TRUNCATED_FILES" -gt 0 ]; then
  echo "[FAIL] ${TRUNCATED_FILES} 个文件存在栈截断：录制 JVM 未设置（或设置过小的）stackdepth"
  echo "       修复：追加 -XX:FlightRecorderOptions=stackdepth=1024（本次实测 0% 截断）"
  exit 1
fi
echo "[OK] 全部文件无栈截断"

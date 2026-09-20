#!/bin/bash
#
# check-netty-leaks.sh — 校验运行日志中是否存在 Netty ByteBuf 泄漏报告
#
# 用法:
#   ./check-netty-leaks.sh <日志文件或目录> [...]           # 只校验既有日志
#   ./check-netty-leaks.sh --run [--clean] [轮数] [额外 mvn 参数...]  # 自行跑 paranoid 全量并校验（默认 1 轮）
#
# --clean: 以 `mvn -o clean test ...` 运行，与 CI（`mvn clean test`）口径一致。不加则复用 target/ 里
#   上一次编译的 class —— 若那批 class 是别的 JDK 构建的，会出现与当前源码无关的失败甚至假泄漏。
#
# 背景:
#   Netty 的 ResourceLeakDetector 仅在 paranoid 级别报告泄漏，且报告走 **java.util.logging**
#   （reportTracedLeak / reportUntracedLeak），行首带平台 locale 的日期前缀——中文 Windows 下
#   该前缀是 GBK 字节，朴素的 UTF-8 grep 会“看不见”这些行（本仓库曾因此误判为 0 泄漏）。
#   另注意默认只保留 4 条 access records，其余被丢弃（日志里会写 "leak records were discarded"），
#   证据可能不完整，故本脚本在 --run 模式下追加 targetRecords=16。
#
# 退出码: 0 = 无泄漏报告；1 = 存在泄漏报告；2 = 用法/环境错误
#
set -uo pipefail

MVN="${MVN:-mvn}"
LEAK_MARKERS='reportTracedLeak|reportUntracedLeak|LEAK: ByteBuf'
RUN=0
CLEAN=0
ROUNDS=1
TARGETS=()
EXTRA_MVN_ARGS=()

if [ $# -eq 0 ]; then
  echo "用法: $0 <日志文件或目录> [...]  |  $0 --run [轮数] [额外 mvn 参数...]" >&2
  exit 2
fi

if [ "$1" = "--run" ]; then
  RUN=1
  shift
  if [ $# -gt 0 ] && [ "$1" = "--clean" ]; then
    CLEAN=1
    shift
  fi
  if [ $# -gt 0 ] && [[ "$1" =~ ^[0-9]+$ ]]; then
    ROUNDS="$1"
    shift
  fi
  EXTRA_MVN_ARGS=("$@")
else
  TARGETS=("$@")
fi

# 单文件校验：输出统计并返回 0/1
check_file() {
  local f="$1" traced untraced slf4j total content head_hex
  # PowerShell 重定向写 UTF-16LE：字节级匹配会把这类日志误判为「无泄漏报告」，必须先解码
  head_hex=$(head -c 2 "$f" 2>/dev/null | od -An -tx1 | tr -d ' \n')
  if [ "$head_hex" = "fffe" ]; then
    content=$(iconv -f UTF-16LE -t UTF-8 "$f" 2>/dev/null || cat "$f")
  else
    content=$(cat "$f")
  fi
  traced=$(printf '%s\n' "$content" | grep -c 'reportTracedLeak' || true)
  untraced=$(printf '%s\n' "$content" | grep -c 'reportUntracedLeak' || true)
  slf4j=$(printf '%s\n' "$content" | grep -c 'LEAK: ByteBuf' || true)
  total=$((traced + untraced + slf4j))
  if [ "$total" -gt 0 ]; then
    printf '%-52s traced=%-4s untraced=%-4s slf4j=%-4s <= 存在泄漏报告\n' \
      "$(basename "$f")" "$traced" "$untraced" "$slf4j"
    return 1
  fi
  printf '%-52s 无泄漏报告 OK\n' "$(basename "$f")"
  return 0
}

# 两类失败分开计数：maven 退出码非 0（构建/用例失败）≠ 存在泄漏报告，混在一起会把
# 「用例失败」误报成「有泄漏」，误导排查方向（实测踩过）。
MVN_FAILED=0
LEAK_FAILED=0
FILES=()

if [ "$RUN" -eq 1 ]; then
  for r in $(seq 1 "$ROUNDS"); do
    log="netty-leak-check-round${r}.log"
    echo "==> [第 ${r}/${ROUNDS} 轮] paranoid 全量测试 -> ${log}"
    # -XshowSettings:properties 用于正面证明 paranoid 确实传入了测试 JVM（否则“无报告”也可能是没生效）
    MVN_GOALS=(-o test)
    if [ "$CLEAN" -eq 1 ]; then
      MVN_GOALS=(-o clean test)
    fi
    "$MVN" "${MVN_GOALS[@]}" -Djacoco.skip=true \
      -DargLine="-Dio.netty.leakDetection.level=paranoid -Dio.netty.leakDetection.targetRecords=16 -XshowSettings:properties" \
      "${EXTRA_MVN_ARGS[@]}" > "$log" 2>&1
    mvn_rc=$?
    if [ "$mvn_rc" -ne 0 ]; then
      echo "    [FAIL] maven 退出码 ${mvn_rc}：构建/用例失败（不等于泄漏，详见 ${log}）" >&2
      MVN_FAILED=$((MVN_FAILED + 1))
    fi
    if ! grep -q 'io.netty.leakDetection.level = paranoid' "$log"; then
      echo "    [WARN] 未在 ${log} 中找到 paranoid 属性转储：无法证明检测级别已生效" >&2
    fi
    FILES+=("$log")
  done
else
  for target in "${TARGETS[@]}"; do
    if [ -d "$target" ]; then
      while IFS= read -r f; do FILES+=("$f"); done < <(find "$target" -maxdepth 1 -name '*.log' | sort)
    elif [ -f "$target" ]; then
      FILES+=("$target")
    else
      echo "跳过（不存在）: $target" >&2
    fi
  done
fi

if [ ${#FILES[@]} -eq 0 ]; then
  echo "错误: 没有可检查的日志文件" >&2
  exit 2
fi

echo ""
echo "=== 泄漏报告统计（匹配: ${LEAK_MARKERS}）==="
for f in "${FILES[@]}"; do
  if ! check_file "$f"; then
    LEAK_FAILED=$((LEAK_FAILED + 1))
  fi
done

echo ""
if [ "$MVN_FAILED" -gt 0 ]; then
  echo "[FAIL] ${MVN_FAILED} 轮 maven 退出码非 0：构建/用例失败（详见对应轮次日志）"
fi
if [ "$LEAK_FAILED" -gt 0 ]; then
  echo "[FAIL] ${LEAK_FAILED} 个日志中存在 Netty ByteBuf 泄漏报告（栈里 Created at: 即分配点）"
  echo "       排查：NettyMultipartWebRequest.release() / 响应写出拒绝路径 / pipelining 引用配平"
fi
if [ "$MVN_FAILED" -gt 0 ] || [ "$LEAK_FAILED" -gt 0 ]; then
  exit 1
fi
echo "[OK] 无 Netty ByteBuf 泄漏报告"

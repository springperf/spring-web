#!/bin/bash
#
# check-docs.sh — 校验受 Git 管理的 Markdown 文档（坏链 / 锚点 / 编码与格式 / 符号）
#
# 用法:
#   ./check-docs.sh                     # 校验全部受管理 *.md
#   ./check-docs.sh --self-test         # 自检（锚点规则、符号规则与检出能力）
#   ./check-docs.sh -v                  # 列出每个被检查的文件
#
# 说明:
#   两个检查器都在 scripts/ 下（Python 3，无第三方依赖），本脚本只做解释器发现，
#   便于 CI（ubuntu-latest 自带 python3）与本地（WSL / Git Bash）用同一条命令调用：
#     - check-docs.py         链接 / 锚点 / 编码与基础格式（锚点规则必须与 GitHub 一致：
#                             删除标点（含 `·` `、` `：`）后**不补 `-`**——详见其文件头）；
#     - check-doc-symbols.py  契约文档点名的**符号是否存在**（配置键、类名、成员）。
#                             它**不检查句子真假**，且其"未知类名"规则只报告不拦截——详见其文件头。
#   两者共用同一套参数（--self-test / --quiet / -v），任一失败即失败。
#
# 退出码: 0 = 通过；1 = 存在缺陷；2 = 用法/环境错误
#
set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PY="${PYTHON:-}"
if [ -z "$PY" ]; then
  # 必须实际执行一次来判定：`command -v` 会命中 Windows Store 的
  # `python`/`python3` 占位stub —— 它存在但执行时打印
  # "Python was not found but can be installed from the Microsoft Store" 并返回非 0，
  # 于是探测通过、真正干活时才失败。
  for candidate in python3 python py; do
    if command -v "$candidate" >/dev/null 2>&1 \
       && "$candidate" -c 'import sys' >/dev/null 2>&1; then
      PY="$candidate"
      break
    fi
  done
fi

if [ -z "$PY" ]; then
  echo "错误: 找不到 python3（可用 PYTHON=/path/to/python3 指定）" >&2
  exit 2
fi

# 保留最高严重度的退出码：check-docs.py 用 2 表示用法/环境错误、1 表示检查未通过。
# 原先第二次赋值会覆盖第一次，两个检查器都失败时只剩后者的码，调用方无法区分用法错误与检查失败。
status=0
"$PY" "$DIR/check-docs.py" "$@" || status=$?
"$PY" "$DIR/check-doc-symbols.py" "$@" || second=$?
if [ "${second:-0}" -gt "$status" ]; then
  status=$second
fi
exit "$status"

#!/bin/bash
#
# check-docs.sh — 校验受 Git 管理的 Markdown 文档（坏链 / 锚点 / 编码与基础格式）
#
# 用法:
#   ./check-docs.sh                     # 校验全部受管理 *.md
#   ./check-docs.sh --self-test         # 自检（验证 GitHub 锚点规则与检出能力）
#   ./check-docs.sh -v                  # 列出每个被检查的文件
#
# 说明:
#   逻辑在 scripts/check-docs.py（Python 3，无第三方依赖）。本脚本仅做解释器发现，
#   便于 CI（ubuntu-latest 自带 python3）与本地（WSL / Git Bash）用同一条命令调用。
#   锚点规则必须与 GitHub 一致：删除标点（含 `·` `、` `：`）后**不补 `-`**——详见 .py 文件头。
#
# 退出码: 0 = 通过；1 = 存在缺陷；2 = 用法/环境错误
#
set -uo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

PY="${PYTHON:-}"
if [ -z "$PY" ]; then
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1; then
      PY="$candidate"
      break
    fi
  done
fi

if [ -z "$PY" ]; then
  echo "错误: 找不到 python3（可用 PYTHON=/path/to/python3 指定）" >&2
  exit 2
fi

exec "$PY" "$DIR/check-docs.py" "$@"

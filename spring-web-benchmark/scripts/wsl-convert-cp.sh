#!/usr/bin/env bash
# =============================================================================
# WSL 侧运行：把 Windows 分号分隔的 classpath 文件转成 WSL 冒号分隔路径。
#
# 用法（由 wsl-server.sh 调用，参数为 WSL 路径）:
#   wsl-convert-cp.sh <cp-raw.txt> <wsl-cp.txt>
#     <cp-raw.txt>  build-classpath 输出，Windows 路径、分号分隔（C:\...;D:\...）
#     <wsl-cp.txt>  输出，WSL 路径、冒号分隔（/mnt/c/...:/mnt/d/...）
#
# 为什么放 WSL 侧：Git Bash（MSYS2）对 sed 命令行参数有路径转换干扰，
# 反斜杠/冒号会被误处理；WSL 内 GNU sed 行为正常。
#
# 转换链:
#   1) \ -> /             （Windows 分隔符）
#   2) ^C: -> /mnt/c      （行首盘符）
#   3) ;C: -> ;/mnt/c     （分隔后的盘符，g 全局）
#   4) ; -> :             （分隔符改为 WSL 冒号）
# =============================================================================
set -euo pipefail

IN="${1:?用法: wsl-convert-cp.sh <in> <out>}"
OUT="${2:?用法: wsl-convert-cp.sh <in> <out>}"

sed -e 's@[\\]@/@g' \
    -e 's@^\([A-Za-z]\):@/mnt/\L\1@' \
    -e 's@;\([A-Za-z]\):@;/mnt/\L\1@g' \
    -e 's@;@:@g' \
    "$IN" > "$OUT"

#!/usr/bin/env bash
# 性能分析脚本的共用环境配置。
#
# 用法：脚本开头 `source "$(dirname "$0")/perf-env.sh"`
#
# 设计原则：**不硬编码任何绝对路径**。所有路径从本文件位置推导，
# 因此整个目录可随worktree 一起移动/克隆。
#
# 唯一需要外部提供的是 WSL 侧的 JDK 路径（WSL_INNER_JAVA_HOME）——
# 它属于环境而非仓库，故做成可覆盖的变量。

# ---- 路径推导 ------------------------------------------------------------
# 脚本自身位置（perf/ 目录）
PERF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# spring-web-benchmark 模块目录
BENCH_DIR="$(cd "$PERF_DIR/.." && pwd)"
# 仓库根目录
REPO_ROOT="$(cd "$BENCH_DIR/.." && pwd)"

# Git Bash 下 /d/xxx 与 Windows JVM 需要的 D:/xxx 是两套路径。
# Java 只认后者，python 只认前者（视安装而定）—— 故两者都提供。
if [ -d "$REPO_ROOT" ]; then
  WIN_REPO="$(cd "$REPO_ROOT" && pwd -W 2>/dev/null || echo "$REPO_ROOT")"
fi
: "${WIN_REPO:=D:/workspace/springperf/spring-web}"   # 兜底（cygpath 不可用时）

# 模块清单：in-process 测量需要把这些模块的 target/classes 放在 jar 之前，
# 否则安装的旧 jar 会遮蔽当前构建产物。
PERF_MODULES="spring-web spring-web-servlet spring-web-view spring-web-mvc-support spring-web-websocket spring-boot-starter-web"

# ---- 外部依赖 ------------------------------------------------------------
# Windows 侧 JDK 17（跑 JMH 客户端用）
: "${JDK17:=D:/soft_space/Java/jdk-17.0.9}"
if [ ! -x "$JDK17/bin/java" ]; then
  # 退回到 PATH 上的 java
  JDK17="$(dirname "$(dirname "$(command -v java)")")"
fi

# jakarta.servlet-api：benchmark 的 pom 里是 provided 作用域，不传递，
# 必须显式补上，否则 @ConditionalOnMissingBean 解析不到
# jakarta.servlet.ServletContext 而启动失败。
: "${SERVLET_JAR:=D:/maven/repository/jakarta/servlet/jakarta.servlet-api/6.1.0/jakarta.servlet-api-6.1.0.jar}"

# 基线 worktree（3.5.6 语义）。做 A/B 对比时作为对照组。
# 用 `git worktree add ../baseline-41 <commit>` 创建；不存在时 A/B 脚本会跳过基线组。
: "${BASELINE_WT:=}"

# python 解释器：所有 .sh 用"$PYEXE" 而非裸 `python`。
# 不能靠 `command -v` 判定 —— Windows Store 的 `python`/`python3` 是占位 stub，
# 存在但执行时打印 "Python was not found..." 并返回非 0，
# 探测会通过、真正解析报告时才失败。必须实际执行一次来判定。
# 命名为 PYEXE 而非 PY：脚本里的 heredoc 分隔符就叫 'PY'，同名易误读。
if [ -z "${PYEXE:-}" ]; then
  for c in python3 python py; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys' >/dev/null 2>&1; then
      PYEXE="$c"; break
    fi
  done
fi
: "${PYEXE:=python}"   # 兜底：找不到时让脚本报错，但不要在 source 阶段就中断

# 报告输出目录
: "${PERF_OUT:=$BENCH_DIR/target/perf-reports}"
# Java 只认Windows 形式路径，故转一道
PERF_OUT_WIN="$(cd "$PERF_OUT" 2>/dev/null && pwd -W 2>/dev/null || echo "$PERF_OUT")"

# ---- 工具函数 ------------------------------------------------------------

# 拼出各模块 target/classes 的 classpath 前缀（Windows 形式，供 -cp 使用）
perf_module_classpath() {
  local mods="${1:-$PERF_MODULES}"
  local out=""
  for m in $mods; do
    out="${out}${out:+;}$WIN_REPO/$m/target/classes"
  done
  echo "$out"
}

# 拼出完整 classpath：模块 classes + benchmark classes + 依赖 jar + servlet-api
perf_classpath() {
  local cp_file="${1:-$BENCH_DIR/target/cp-perf.txt}"
  if [ ! -f "$cp_file" ]; then
    echo "ERROR: 缺少 $cp_file —— 先执行 scripts/build-classpath 或见方法论文档 §6" >&2
    return 1
  fi
  echo "$(perf_module_classpath);$WIN_REPO/spring-web-benchmark/target/classes;$(cat "$cp_file");$SERVLET_JAR"
}

# 删除 JMH 全局锁（异常退出会残留，导致下次启动失败）
perf_clear_jmh_lock() {
  rm -f "${TEMP:-/tmp}/jmh.lock" 2>/dev/null
  return 0
}

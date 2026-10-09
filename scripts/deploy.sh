#!/bin/bash
# 发布脚本。set -euo pipefail 让任一 mvn 失败立即中断：
# 否则失败的构建会被静默吞掉，脚本仍以 0 退出，CI 会误判发布成功。
set -euo pipefail

# 脚本位于 scripts/，仓库根为其父目录（-pl 的模块路径与 ./pom.xml 都相对仓库根，故须在根目录执行）
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 优先用仓库自带 Wrapper（与 CI 同一 Maven 版本 3.9.9），而不是 PATH 上的 mvn：
# 实测系统 Maven 3.8.6 会在父 POM 的 spotbugs-report 处直接失败——
# "com.github.spotbugs:spotbugs-maven-plugin:4.10.4.1 requires Maven version 3.8.9"，
# 一个业务模块都跑不到（Reactor 里其余模块全 SKIPPED）。同因见 docs/feature/jfr-cpu-hotspot-analysis.md。
if [ -x "$REPO_ROOT/mvnw" ]; then MVN="$REPO_ROOT/mvnw"; else MVN="mvn"; fi

"$MVN" clean deploy -P release -DskipTests  -pl .,spring-web,spring-web-view,spring-web-websocket,spring-web-servlet,spring-web-mvc-support,spring-web-batch,spring-boot-starter-web \
  "$@"
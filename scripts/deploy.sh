#!/bin/bash
# 发布脚本。set -euo pipefail 让任一 mvn 失败立即中断：
# 否则失败的构建会被静默吞掉，脚本仍以 0 退出，CI 会误判发布成功。
set -euo pipefail

mvn clean deploy -P release -DskipTests  -pl .,spring-web,spring-web-view,spring-web-websocket,spring-web-servlet,spring-web-mvc-support,spring-web-batch,spring-boot-starter-web \
  "$@"
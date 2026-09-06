#!/usr/bin/env bash
#
# GraalVM native-image 冒烟测试（Linux 环境）
#
# 前置要求：
#   - Linux 环境（epoll native transport 在 Windows 上受限）
#   - GraalVM JDK 17+（native-image 在 PATH）或 `gu install native-image`
#   - Maven 3.8+
#
# 用法：
#   ./scripts/native-smoke-test.sh [<module>] [<health-path>]
#
# 默认：spring-web-examples/spring-web-example-rest 的 /health
#
set -euo pipefail

MODULE="${1:-spring-web-examples/spring-web-example-rest}"
HEALTH_PATH="${2:-/health}"
ARTIFACT_ID="spring-web-example-rest"
PORT="18080"

command -v native-image >/dev/null 2>&1 || {
    echo "ERROR: native-image 不在 PATH，请安装 GraalVM 并启用 native-image" >&2
    exit 1
}

echo "==> 1/4 构建原生镜像（mvn -Pnative install：依赖模块 JVM 构建，目标模块原生编译）"
mvn -q -Pnative -pl "${MODULE}" -am install -DskipTests

BIN="${MODULE}/target/${ARTIFACT_ID}"
if [ ! -x "${BIN}" ]; then
    echo "ERROR: 未找到原生可执行文件 ${BIN}（模块与产物名不匹配？）" >&2
    exit 1
fi

echo "==> 2/4 启动原生镜像（端口 ${PORT}）"
"${BIN}" --server.port="${PORT}" >/tmp/spring-web-native.log 2>&1 &
APP_PID=$!
trap 'kill "${APP_PID}" 2>/dev/null || true' EXIT

echo "==> 3/4 等待就绪"
for i in $(seq 1 60); do
    if curl -sf "http://127.0.0.1:${PORT}${HEALTH_PATH}" >/dev/null 2>&1; then
        break
    fi
    if ! kill -0 "${APP_PID}" 2>/dev/null; then
        echo "ERROR: 原生进程提前退出，日志如下：" >&2
        cat /tmp/spring-web-native.log >&2
        exit 1
    fi
    sleep 1
done

echo "==> 4/4 断言 ${HEALTH_PATH} 返回 200"
CODE=$(curl -s -o /tmp/spring-web-native-body -w '%{http_code}' "http://127.0.0.1:${PORT}${HEALTH_PATH}")
echo "HTTP ${CODE}: $(cat /tmp/spring-web-native-body)"
[ "${CODE}" = "200" ] || {
    echo "ERROR: 期望 200，实际 ${CODE}" >&2
    exit 1
}

echo "PASS: native-image 冒烟测试通过"

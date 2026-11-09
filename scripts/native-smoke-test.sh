#!/usr/bin/env bash
#
# GraalVM native-image 冒烟测试（Linux 环境）
#
# 前置要求：
#   - Linux 环境（epoll native transport 在 Windows 上受限）
#   - GraalVM JDK 17+（native-image 在 PATH）或 `gu install native-image`
#   - Maven 3.8.9+（推荐直接用仓库自带 ./mvnw，脚本已优先使用它）
#   - curl
#
# 用法：
#   ./scripts/native-smoke-test.sh
#
# 断言用例绑定 spring-web-example-rest 的 REST 契约。每条都对应一类 AOT 可达性提示
# （见 docs/internals/14-starter-autoconfig.md §5.3），不是单纯探活：
#   /health                  → Jackson 序列化 + ApiResult DTO hints
#   GET/POST/PUT/DELETE      → @Controller 方法反射、@PathVariable、@RequestBody 绑定
#   /api/users/99999         → 业务错误分支
#   /no-such-route           → 框架 404 分支
# 视图 / 异步 / 独立管理端口不在此覆盖范围内：示例模块不含模板与 management.server.port，
# 且只有本模块绑定了 process-aot + native profile（新增模块需先补齐这两项）。
# 本脚本不做通用化参数：模块、产物名与用例是同一份契约，拆开会给出「可换模块」的假象。
#
set -euo pipefail

# 脚本位于 scripts/，仓库根为其父目录（MODULE 是相对仓库根的路径，故须在根目录执行）
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 优先用仓库自带 Wrapper（与 CI 同一 Maven 版本），而不是 PATH 上的 mvn：
# spotbugs-maven-plugin 4.10.4.1 要求 Maven ≥ 3.8.9，系统 Maven 过低会在父 POM 处直接失败，
# 整个 Reactor 其余模块全 SKIPPED。
if [ -x "$REPO_ROOT/mvnw" ]; then MVN="$REPO_ROOT/mvnw"; else MVN="mvn"; fi

MODULE="spring-web-examples/spring-web-example-rest"
ARTIFACT_ID="spring-web-example-rest"
PORT="18080"
HEALTH_PATH="/health"
BASE_URL="http://127.0.0.1:${PORT}"
CURL_TIMEOUT=10
READY_ATTEMPTS=60

# 临时文件放在脚本私有的 mktemp -d 目录，而不是固定的 /tmp/spring-web-native.log。
# 固定路径在多用户机器上可被预先创建为符号链接（符号链接攻击），并发执行同一脚本的两轮
# 也会互相覆盖日志导致排障困难。目录由 EXIT trap 一并清理。
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/spring-web-native.XXXXXX")"
LOG_FILE="${WORK_DIR}/app.log"
BODY_FILE="${WORK_DIR}/body"
cleanup() {
    kill "${APP_PID:-}" 2>/dev/null || true
    rm -rf "${WORK_DIR}"
}
trap cleanup EXIT

command -v curl >/dev/null 2>&1 || {
    echo "ERROR: curl 不在 PATH" >&2
    exit 1
}
command -v native-image >/dev/null 2>&1 || {
    echo "ERROR: native-image 不在 PATH，请安装 GraalVM 并启用 native-image" >&2
    exit 1
}

# 统一请求出口：curl 失败（连接拒绝/超时）记为 000，交给 assert_response 给出可诊断的失败信息，
# 不让 set -e 在一个没有任何上下文的退出码上直接中断。
HTTP_CODE=""
request() {
    local method="$1" path="$2" data="${3-}"
    local -a args=(-sS -m "${CURL_TIMEOUT}" -o "${BODY_FILE}" -w '%{http_code}'
                   -X "${method}" "${BASE_URL}${path}")
    if [ -n "${data}" ]; then
        args+=(-H 'Content-Type: application/json' --data "${data}")
    fi
    HTTP_CODE="$(curl "${args[@]}")" || HTTP_CODE="000"
}

fail() {
    local label="$1" expected="$2"
    echo "ERROR: ${label}" >&2
    echo "  expected: ${expected}" >&2
    echo "  actual  : HTTP ${HTTP_CODE} $(cat "${BODY_FILE}" 2>/dev/null || true)" >&2
    echo "--- native app log (tail) ---" >&2
    tail -n 40 "${LOG_FILE}" >&2 || true
    exit 1
}

assert_response() {
    local label="$1" expected_code="$2" expected_fragment="${3-}"
    if [ "${HTTP_CODE}" != "${expected_code}" ]; then
        fail "${label}" "HTTP ${expected_code}"
    fi
    if [ -n "${expected_fragment}" ] && ! grep -Fq "${expected_fragment}" "${BODY_FILE}"; then
        fail "${label}" "body containing ${expected_fragment}"
    fi
    echo "  OK ${label} (HTTP ${HTTP_CODE})"
}

echo "==> 1/4 构建原生镜像（${MVN} -Pnative install：依赖模块 JVM 构建，目标模块原生编译）"
"${MVN}" -q -Pnative -pl "${MODULE}" -am install -DskipTests

BIN="${MODULE}/target/${ARTIFACT_ID}"
if [ ! -x "${BIN}" ]; then
    echo "ERROR: 未找到原生可执行文件 ${BIN}（模块与产物名不匹配？）" >&2
    exit 1
fi

echo "==> 2/4 启动原生镜像（端口 ${PORT}）"
"${BIN}" --server.port="${PORT}" >"${LOG_FILE}" 2>&1 &
APP_PID=$!

echo "==> 3/4 等待就绪"
# 就绪探测单独用静默 curl：服务尚未监听时 request() 的 -sS 会重复打印连接拒绝，
# 且 10s 请求超时会把 60 次重试放大到 10 分钟。这里用 2s 短超时、只看 2xx。
READY=0
for _ in $(seq 1 "${READY_ATTEMPTS}"); do
    if curl -sf -m 2 -o /dev/null "${BASE_URL}${HEALTH_PATH}" 2>/dev/null; then
        READY=1
        break
    fi
    if ! kill -0 "${APP_PID}" 2>/dev/null; then
        echo "ERROR: 原生进程提前退出，日志如下：" >&2
        cat "${LOG_FILE}" >&2
        exit 1
    fi
    sleep 1
done
if [ "${READY}" != "1" ]; then
    echo "ERROR: ${READY_ATTEMPTS} 次探测内 ${HEALTH_PATH} 未就绪" >&2
    tail -n 40 "${LOG_FILE}" >&2 || true
    exit 1
fi

echo "==> 4/4 请求链路断言（8 条，覆盖 AOT hints 实际生效路径）"

request GET "${HEALTH_PATH}"
assert_response "health / Jackson 序列化 + ApiResult DTO" 200 '"data":"OK"'

request GET "/api/users"
assert_response "用户列表 / Controller 方法反射" 200 '"code":0'

request POST "/api/users" '{"name":"alice","email":"alice@example.com","age":25}'
assert_response "创建用户 / @RequestBody + @Valid + User DTO 反序列化" 200 '"name":"alice"'
# id 由示例的 AtomicLong 生成，不能硬编码：从创建响应里取，避免镜像重启后 id 漂移。
USER_ID="$(grep -o '"id":[0-9]*' "${BODY_FILE}" | head -n 1 | cut -d: -f2 || true)"
if [ -z "${USER_ID}" ]; then
    echo "ERROR: 未能从创建响应解析用户 id" >&2
    exit 1
fi

request GET "/api/users/${USER_ID}"
assert_response "按 id 查询 / @PathVariable 绑定" 200 '"name":"alice"'

request PUT "/api/users/${USER_ID}" '{"name":"alice-updated","email":"alice-new@example.com","age":26}'
assert_response "更新用户 / PUT 路由 + 请求体绑定" 200 '"name":"alice-updated"'

request DELETE "/api/users/${USER_ID}"
assert_response "删除用户 / DELETE 路由" 200 '"code":0'

request GET "/api/users/99999"
assert_response "查询不存在用户 / 业务错误分支" 200 '"user not found"'

request GET "/no-such-route"
assert_response "未知路由 / 框架 404 分支" 404

echo "PASS: native-image 冒烟测试通过（8 条请求链路断言）"

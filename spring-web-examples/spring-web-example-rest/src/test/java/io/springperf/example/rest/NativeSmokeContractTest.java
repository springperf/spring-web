package io.springperf.example.rest;

import io.springperf.example.rest.controller.HealthController;
import io.springperf.example.rest.controller.UserController;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把 native smoke 脚本与示例 REST 契约的对应关系写成可执行台账。
 *
 * <p>脚本本身只能在 Linux + GraalVM 上实跑，而文档声称它做「请求链路断言」。
 * 这里锁定三件事：脚本确实覆盖了各类 AOT hints 对应的端点、控制器路由没有漂移、
 * CI workflow 仍在调用该脚本。任一侧退化（例如脚本退回只探活 /health）都会在此失败。
 */
class NativeSmokeContractTest {

    @Test
    void smokeScript_assertsFullRequestChain() throws IOException {
        String script = repoFile("scripts/native-smoke-test.sh");

        assertThat(script)
                .contains("request GET \"/api/users\"")
                .contains("request POST \"/api/users\"")
                .contains("request PUT \"/api/users/")
                .contains("request DELETE \"/api/users/")
                .contains("request GET \"/no-such-route\"")
                .contains("Content-Type: application/json")
                .contains("assert_response \"未知路由 / 框架 404 分支\" 404");

        assertThat(countOccurrences(script, "assert_response \""))
                .as("8 条请求链路断言，防止退回单端点探活")
                .isGreaterThanOrEqualTo(8);
    }

    @Test
    void controllerRoutes_matchSmokePaths() {
        Map<String, Set<String>> routes = new TreeMap<>();
        collect(HealthController.class, routes);
        collect(UserController.class, routes);

        assertThat(routes.keySet())
                .containsExactlyInAnyOrder("/health", "/api/users", "/api/users/{id}");
        assertThat(routes.get("/health")).containsExactly("GET");
        assertThat(routes.get("/api/users")).containsExactlyInAnyOrder("GET", "POST");
        assertThat(routes.get("/api/users/{id}")).containsExactlyInAnyOrder("GET", "PUT", "DELETE");
    }

    @Test
    void nativeImageWorkflow_runsSmokeScript() throws IOException {
        assertThat(repoFile(".github/workflows/native-image.yml"))
                .contains("graalvm/setup-graalvm")
                .contains("bash ./scripts/native-smoke-test.sh");
    }

    private static void collect(Class<?> controller, Map<String, Set<String>> routes) {
        RequestMapping type = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        String prefix = type != null && type.value().length > 0 ? type.value()[0] : "";
        for (Method method : controller.getDeclaredMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            String path = prefix + (mapping.value().length > 0 ? mapping.value()[0] : "");
            for (RequestMethod httpMethod : mapping.method()) {
                routes.computeIfAbsent(path, key -> new TreeSet<>()).add(httpMethod.name());
            }
        }
    }

    /** 从 surefire 的工作目录逐级向上找仓库根，避免依赖固定的相对层级。 */
    private static String repoFile(String relative) throws IOException {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path current = dir; current != null; current = current.getParent()) {
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Cannot locate " + relative + " from " + dir);
    }

    private static int countOccurrences(String text, String fragment) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(fragment, from)) >= 0) {
            count++;
            from += fragment.length();
        }
        return count;
    }
}

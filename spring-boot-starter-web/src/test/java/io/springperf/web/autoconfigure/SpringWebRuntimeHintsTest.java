package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.support.PerfWebServer;
import io.springperf.web.autoconfigure.support.PerfWebServerInitializedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.JdkProxyHint;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeHint;
import org.springframework.aot.hint.TypeReference;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link SpringWebRuntimeHints} 的事件路径可达性提示。 JVM 模式下本 registrar 不被调用（由 Spring AOT 构建期触发），本测试直接驱动它验证注册结果。
 * <p>
 * 早期版本还要验证「SB4 类型仅在 classpath 存在时注册」的条件语义（当时测试 classpath
 * 用桩类模拟）。本分支专用 Spring Boot 4，SB4 类型是真实依赖，条件注册已移除，故只测正面注册。
 * </p>
 */
class SpringWebRuntimeHintsTest {

    private final SpringWebRuntimeHints registrar = new SpringWebRuntimeHints();

    @Test
    void registersEventPathHints() {
        RuntimeHints hints = register();

        assertJdkProxy(hints, WebServerApplicationContext.class);
        assertReflectionType(hints, PerfWebServer.class);
        assertReflectionType(hints, PerfWebServerInitializedEvent.class);
    }

    @Test
    void registersResourceHints() {
        RuntimeHints hints = register();

        boolean hasResources = hints.resources().resourcePatternHints().findAny().isPresent();
        assertTrue(hasResources, "应注册 classpath 资源提示（元数据/模板/静态资源）");
    }

    private RuntimeHints register() {
        RuntimeHints hints = new RuntimeHints();
        registrar.registerHints(hints, getClass().getClassLoader());
        return hints;
    }

    private static void assertJdkProxy(RuntimeHints hints, Class<?> iface) {
        boolean registered = hints.proxies().jdkProxyHints().map(JdkProxyHint::getProxiedInterfaces)
                .anyMatch(ifaces -> ifaces.contains(TypeReference.of(iface)));
        assertTrue(registered, "JDK proxy hint missing for " + iface.getName());
    }

    private static void assertReflectionType(RuntimeHints hints, Class<?> type) {
        boolean registered = hints.reflection().typeHints().map(TypeHint::getType)
                .anyMatch(t -> t.getName().equals(type.getName()));
        assertTrue(registered, "Reflection hint missing for " + type.getName());
    }
}

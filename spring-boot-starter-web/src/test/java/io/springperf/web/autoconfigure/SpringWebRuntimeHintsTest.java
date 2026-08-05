package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.support.PerfWebServer;
import io.springperf.web.autoconfigure.support.PerfWebServerInitializedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.JdkProxyHint;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeHint;
import org.springframework.aot.hint.TypeReference;
import org.springframework.boot.web.context.WebServerApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@link SpringWebRuntimeHints} 的事件路径可达性提示：
 * SB3 事件/上下文类型固定注册，SB4 类型仅在其存在时注册（classpath 缺失时静默跳过）。
 * JVM 模式下本 registrar 不被调用（由 Spring AOT 构建期触发），本测试直接驱动它验证注册结果。
 */
class SpringWebRuntimeHintsTest {

    private final SpringWebRuntimeHints registrar = new SpringWebRuntimeHints();

    @Test
    void registersSb3EventPath() {
        RuntimeHints hints = register();

        assertJdkProxy(hints, WebServerApplicationContext.class);
        assertReflectionType(hints, PerfWebServer.class);
        assertReflectionType(hints, PerfWebServerInitializedEvent.class);
    }

    @Test
    void sb4TypesSkippedWhenAbsentFromClasspath() {
        RuntimeHints hints = register();

        // SB3 classpath 无 SB4 新包名，registerTypeIfPresent 静默跳过，不得注册、不得抛异常
        boolean sb4Reflected = hints.reflection().typeHints()
                .map(TypeHint::getType)
                .anyMatch(t -> t.getName().startsWith("org.springframework.boot.web.server.context."));
        boolean sb4Proxied = hints.proxies().jdkProxyHints()
                .map(JdkProxyHint::getProxiedInterfaces)
                .flatMap(java.util.List::stream)
                .anyMatch(t -> t.getName().startsWith("org.springframework.boot.web.server.context."));

        assertFalse(sb4Reflected, "SB4 event/context types must not be registered when absent");
        assertFalse(sb4Proxied, "SB4 context proxy must not be registered when absent");
    }

    private RuntimeHints register() {
        RuntimeHints hints = new RuntimeHints();
        registrar.registerHints(hints, getClass().getClassLoader());
        return hints;
    }

    private static void assertJdkProxy(RuntimeHints hints, Class<?> iface) {
        boolean registered = hints.proxies().jdkProxyHints()
                .map(JdkProxyHint::getProxiedInterfaces)
                .anyMatch(ifaces -> ifaces.contains(TypeReference.of(iface)));
        assertTrue(registered, "JDK proxy hint missing for " + iface.getName());
    }

    private static void assertReflectionType(RuntimeHints hints, Class<?> type) {
        boolean registered = hints.reflection().typeHints()
                .map(TypeHint::getType)
                .anyMatch(t -> t.getName().equals(type.getName()));
        assertTrue(registered, "Reflection hint missing for " + type.getName());
    }
}

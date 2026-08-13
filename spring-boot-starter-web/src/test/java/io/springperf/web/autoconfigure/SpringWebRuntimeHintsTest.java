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
 * SB3 事件/上下文类型固定注册，SB4 类型仅在其存在时注册（条件注册语义）。
 * JVM 模式下本 registrar 不被调用（由 Spring AOT 构建期触发），本测试直接驱动它验证注册结果。
 *
 * <p>测试 classpath 提供 SB4 包名桩（{@code org.springframework.boot.web.server.context.*}，
 * 见 Boot4WebServerInitializedEventBridgeTest），故 SB4 类型"存在"，验证注册的正面语义；
 * 真实 SB3 运行时 classpath 无 SB4 类时 registerTypeIfPresent 静默跳过，由运行时保证。</p>
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
    void sb4TypesRegisteredWhenPresentInClasspath() {
        RuntimeHints hints = register();

        // 测试 classpath 有 SB4 包名桩，registerTypeIfPresent 应注册 SB4 事件/上下文类型
        // 的反射提示与上下文接口的 JDK 代理提示（条件注册的正面语义）。
        boolean sb4Reflected = hints.reflection().typeHints()
                .map(TypeHint::getType)
                .anyMatch(t -> t.getName().startsWith("org.springframework.boot.web.server.context."));
        boolean sb4Proxied = hints.proxies().jdkProxyHints()
                .map(JdkProxyHint::getProxiedInterfaces)
                .flatMap(java.util.List::stream)
                .anyMatch(t -> t.getName().startsWith("org.springframework.boot.web.server.context."));

        assertTrue(sb4Reflected, "SB4 桩存在时应注册事件/上下文类型反射提示");
        assertTrue(sb4Proxied, "SB4 桩存在时应注册上下文 JDK 代理提示");
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

package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.support.PerfWebServer;
import io.springperf.web.autoconfigure.support.PerfWebServerInitializedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.JdkProxyHint;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeHint;
import org.springframework.aot.hint.TypeReference;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.util.concurrent.ListenableFutureCallback;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@link SpringWebRuntimeHints} 的可达性提示：事件路径与 ListenableFuture 异步回调路径。
 * JVM 模式下本 registrar 不被调用（由 Spring AOT 构建期触发），本测试直接驱动它验证注册结果。
 */
class SpringWebRuntimeHintsTest {

    private final SpringWebRuntimeHints registrar = new SpringWebRuntimeHints();

    @Test
    void registersEventPath() {
        RuntimeHints hints = register();

        assertJdkProxy(hints, WebServerApplicationContext.class);
        assertReflectionType(hints, PerfWebServer.class);
        assertReflectionType(hints, PerfWebServerInitializedEvent.class);
    }

    @Test
    void registersListenableFutureCallbackPath() {
        RuntimeHints hints = register();

        assertJdkProxy(hints, ListenableFutureCallback.class);
        assertReflectionType(hints, ListenableFutureCallback.class);
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

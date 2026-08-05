package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.support.PerfWebServer;
import io.springperf.web.autoconfigure.support.PerfWebServerInitializedEvent;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.boot.web.context.WebServerApplicationContext;

/**
 * GraalVM native-image 可达性提示（由 {@code @ImportRuntimeHints} 在 Spring AOT 构建期采集，
 * JVM 运行时完全不触发，零影响）。
 *
 * <p>注册 {@code WebServerInitializedEvent} 事件路径所需的两类元数据：
 * <ul>
 *   <li>JDK 动态代理：SB3 事件用代理把真实上下文包装为 {@link WebServerApplicationContext}，
 *        native 下代理接口必须显式注册；</li>
 *   <li>反射：SB4 桥接按名 {@code Class.forName} 加载事件类/上下文接口
 *        （{@link io.springperf.web.autoconfigure.support.Boot4WebServerInitializedEventBridge}）。</li>
 * </ul>
 *
 * <p>{@code @ImportRuntimeHints} 挂在 SB3 专属配置
 * {@link io.springperf.web.autoconfigure.WebServerInitializedEventAutoConfiguration} 上：
 * SB3 下配置类加载、registrar 在 AOT 构建期执行；SB4 下配置类不加载、registrar 不执行，
 * 避免编译期引用的 SB3 类型（{@link WebServerApplicationContext} 等）在 SB4 classpath 缺失时
 * 引发类解析失败。SB4 事件路径在 native 下本就不支持（桥接需运行时 {@code defineClass}），
 * 故 SB4 无提示需求。
 *
 * <p>registrar 内 SB4 类型仍按名条件注册（{@code registerTypeIfPresent}）：当前触发源下
 * SB3 classpath 恒无 SB4 类、自动跳过，仅保留完整的条件注册语义供将来扩展。
 */
public class SpringWebRuntimeHints implements RuntimeHintsRegistrar {

    private static final String SB4_EVENT_CLASS = "org.springframework.boot.web.server.context.WebServerInitializedEvent";
    private static final String SB4_CONTEXT_IFACE = "org.springframework.boot.web.server.context.WebServerApplicationContext";
    private static final String SB4_WEBSERVER_IFACE = "org.springframework.boot.web.server.WebServer";

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // SB3 事件路径：JDK 动态代理包装 WebServerApplicationContext
        hints.proxies().registerJdkProxy(WebServerApplicationContext.class);
        hints.reflection().registerType(PerfWebServer.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(PerfWebServerInitializedEvent.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);

        // SB4 桥接按名反射加载的类型（仅 classpath 存在时注册）
        hints.reflection().registerTypeIfPresent(classLoader, SB4_EVENT_CLASS);
        hints.reflection().registerTypeIfPresent(classLoader, SB4_CONTEXT_IFACE, MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerTypeIfPresent(classLoader, SB4_WEBSERVER_IFACE);
        if (isPresent(classLoader, SB4_CONTEXT_IFACE)) {
            hints.proxies().registerJdkProxy(TypeReference.of(SB4_CONTEXT_IFACE));
        }
    }

    private static boolean isPresent(ClassLoader classLoader, String name) {
        try {
            Class.forName(name, false, classLoader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}

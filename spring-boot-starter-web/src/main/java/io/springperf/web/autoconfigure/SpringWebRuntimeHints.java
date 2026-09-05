package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.support.PerfWebServer;
import io.springperf.web.autoconfigure.support.PerfWebServerInitializedEvent;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.aot.hint.support.FilePatternResourceHintsRegistrar;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.util.ClassUtils;

/**
 * GraalVM native-image 可达性提示（由 {@code @ImportRuntimeHints} 在 Spring AOT 构建期采集，
 * JVM 运行时完全不触发，零影响）。
 *
 * <p>注册四类元数据：
 * <ul>
 *   <li>JDK 动态代理：SB3 事件用代理把真实上下文包装为 {@link WebServerApplicationContext}，
 *       native 下代理接口必须显式注册；异步返回 {@code ListenableFuture} 时
 *       {@link io.springperf.web.core.retval.resolver.async.ListenableFutureAdapter}
 *       用 JDK 代理包装 {@code ListenableFutureCallback}，同样需显式注册；</li>
 *   <li>反射：SB4 桥接按名 {@code Class.forName} 加载事件类/上下文接口
 *       （{@link io.springperf.web.autoconfigure.support.Boot4WebServerInitializedEventBridge}）；
 *       {@code ListenableFuture#addCallback} 按名反射调用；</li>
 *   <li>资源：框架强依赖的 classpath 资源（配置元数据、视图模板、静态资源目录）；</li>
 *   <li>用户 {@code @Controller} 方法与 DTO 的反射/序列化提示由
 *       {@link io.springperf.web.autoconfigure.support.ControllerBeanFactoryInitializationAotProcessor}
 *       在 AOT 构建期按 BeanFactory 实际内容注册（本 registrar 不重复）。</li>
 * </ul>
 *
 * <p>{@code @ImportRuntimeHints} 挂在 SB3 专属配置
 * {@link io.springperf.web.autoconfigure.WebServerInitializedEventAutoConfiguration} 上：
 * SB3 下配置类加载、registrar 在 AOT 构建期执行；SB4 下配置类不加载、registrar 不执行，
 * 避免编译期引用的 SB3 类型（{@link WebServerApplicationContext} 等）在 SB4 classpath 缺失时
 * 引发类解析失败。SB4 事件路径在 native 下本就不支持（桥接需运行时 {@code defineClass}），
 * 故 SB4 无提示需求。
 *
 * <p>registrar 内 SB4 / {@code ListenableFuture} 类型均按名条件注册（{@code registerTypeIfPresent}）：
 * 当前触发源下缺失的类自动跳过，仅保留完整的条件注册语义供将来扩展。
 */
public class SpringWebRuntimeHints implements RuntimeHintsRegistrar {

    private static final String SB4_EVENT_CLASS = "org.springframework.boot.web.server.context.WebServerInitializedEvent";
    private static final String SB4_CONTEXT_IFACE = "org.springframework.boot.web.server.context.WebServerApplicationContext";
    private static final String SB4_WEBSERVER_IFACE = "org.springframework.boot.web.server.WebServer";

    /** Spring Framework 6.x 存在、7.x 移除的 {@code ListenableFuture} 兼容类型 */
    private static final String LISTENABLE_FUTURE_CLASS = "org.springframework.util.concurrent.ListenableFuture";
    private static final String LISTENABLE_FUTURE_CALLBACK_CLASS = "org.springframework.util.concurrent.ListenableFutureCallback";

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

        // ListenableFuture 异步返回路径：JDK 代理 + addCallback 反射（Spring 6.x 存在才注册）
        registerListenableFutureHints(hints, classLoader);

        // 框架强依赖的 classpath 资源
        registerResourceHints(hints, classLoader);
    }

    private static void registerListenableFutureHints(RuntimeHints hints, ClassLoader classLoader) {
        if (!isPresent(classLoader, LISTENABLE_FUTURE_CLASS)) {
            return;
        }
        // ListenableFutureAdapter: Proxy.newProxyInstance(ListenableFutureCallback.class)
        hints.proxies().registerJdkProxy(TypeReference.of(LISTENABLE_FUTURE_CALLBACK_CLASS));
        // ListenableFutureAdapter: future.getClass().getMethod("addCallback", callbackClass).invoke(...)
        hints.reflection().registerTypeIfPresent(classLoader, LISTENABLE_FUTURE_CALLBACK_CLASS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerTypeIfPresent(classLoader, LISTENABLE_FUTURE_CLASS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
    }

    private static void registerResourceHints(RuntimeHints hints, ClassLoader classLoader) {
        // 配置元数据（IDE 提示，运行时被 Spring Boot 工具读取）
        if (classLoader.getResource("META-INF/additional-spring-configuration-metadata.json") != null) {
            hints.resources().registerPattern("META-INF/additional-spring-configuration-metadata.json");
        }
        // 视图模板（spring-web-view）与静态资源目录：扫描 classpath 目录下实际存在的文件逐个注册
        // （比 registerPattern("templates/") 精确——后者只匹配目录路径本身，不覆盖目录内文件）
        FilePatternResourceHintsRegistrar
                .forClassPathLocations("templates/", "static/", "META-INF/resources/", "public/")
                .registerHints(hints.resources(), classLoader);
    }

    private static boolean isPresent(ClassLoader classLoader, String name) {
        return ClassUtils.isPresent(name, classLoader);
    }
}

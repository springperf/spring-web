package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.support.PerfWebServer;
import io.springperf.web.autoconfigure.support.PerfWebServerInitializedEvent;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.support.FilePatternResourceHintsRegistrar;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

/**
 * GraalVM native-image 可达性提示（由 {@code @ImportRuntimeHints} 在 Spring AOT 构建期采集， JVM 运行时完全不触发，零影响）。
 * <p>
 * 注册四类元数据：
 * <ul>
 * <li>JDK 动态代理：事件用代理把真实上下文包装为 {@link WebServerApplicationContext}， native 下代理接口必须显式注册；异步返回 {@code ListenableFuture} 时
 * {@link io.springperf.web.core.retval.resolver.async.ListenableFutureAdapter} 注册 {@link ListenableFutureCallback}
 * 的回调实现；</li>
 * <li>反射：{@code ListenableFuture#addCallback} 回调路径；</li>
 * <li>资源：框架强依赖的 classpath 资源（配置元数据、视图模板、静态资源目录）；</li>
 * <li>用户 {@code @Controller} 方法与 DTO 的反射/序列化提示由
 * {@link io.springperf.web.autoconfigure.support.ControllerBeanFactoryInitializationAotProcessor} 在 AOT 构建期按
 * BeanFactory 实际内容注册（本 registrar 不重复）。</li>
 * </ul>
 */
public class SpringWebRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // 事件路径：JDK 动态代理包装 WebServerApplicationContext
        hints.proxies().registerJdkProxy(WebServerApplicationContext.class);
        hints.reflection().registerType(PerfWebServer.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(PerfWebServerInitializedEvent.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);

        // RuntimeHintsRegistrar 的契约允许 classLoader 为 null（上游标注 @Nullable），而以下注册
        // 全部依赖它做 classpath 探测（getResource），故整体跳过。
        if (classLoader == null) {
            return;
        }

        // ListenableFuture 异步返回路径：代理 + 回调反射
        hints.proxies().registerJdkProxy(ListenableFutureCallback.class);
        hints.reflection().registerType(ListenableFutureCallback.class, MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerType(ListenableFuture.class, MemberCategory.INVOKE_PUBLIC_METHODS);

        // 框架强依赖的 classpath 资源
        registerResourceHints(hints, classLoader);
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
}

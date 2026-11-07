package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.support.PerfWebServer;
import io.springperf.web.autoconfigure.support.PerfWebServerInitializedEvent;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.support.FilePatternResourceHintsRegistrar;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

/**
 * GraalVM native-image 可达性提示（由 {@code @ImportRuntimeHints} 在 Spring AOT 构建期采集， JVM 运行时完全不触发，零影响）。
 * <p>
 * 注册四类元数据：
 * <ul>
 * <li>JDK 动态代理：事件用代理把真实上下文包装为 {@link WebServerApplicationContext} +
 * {@link ConfigurableApplicationContext}， native 下代理接口必须显式注册；异步返回 {@code ListenableFuture} 时
 * {@link io.springperf.web.core.retval.resolver.async.ListenableFutureAdapter} 注册 {@link ListenableFutureCallback}
 * 的回调实现；</li>
 * <li>反射：{@code ListenableFuture#addCallback} 回调路径；</li>
 * <li>资源：框架强依赖的 classpath 资源（配置元数据、视图模板、静态资源目录）；</li>
 * <li>用户 {@code @Controller} 方法与 DTO 的反射/序列化提示由
 * {@link io.springperf.web.autoconfigure.support.ControllerBeanFactoryInitializationAotProcessor} 在 AOT 构建期按
 * BeanFactory 实际内容注册（本 registrar 不重复）。</li>
 * </ul>
 * <p>
 * <b>与静态 {@code META-INF/native-image/.../*.json} 的关系</b>：两轨互补，不是互相替代。
 * 静态 {@code reflect-config.json} / {@code proxy-config.json} 提供 AOT 无法自动发现的框架内部类、
 * registry/resolver/provider 与 SPI 接口基线；静态 {@code resource-config.json} 提供 {@code spring.factories}、
 * {@code .imports} 与配置文件兜底。本 registrar 只补 AOT 构建期才确定或 Spring Boot AOT 不感知的部分。
 * 事件用的双接口代理在两轨重复登记是有意的双保险：Spring Boot native 构建始终经 {@code process-aot}，
 * 静态条目与 AOT 生成配置会同时生效，保留可避免改变既有行为。
 * 两轨的重复/互补/独占关系由 {@code NativeImageHintsConsistencyTest} 锁定。
 * </p>
 * <p>
 * {@code classLoader} 为 null 是 {@link RuntimeHintsRegistrar} 契约允许的输入；只有资源探测需要它，
 * 代理/反射注册与 classLoader 无关，必须始终执行。
 * </p>
 */
public class SpringWebRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // 事件路径：JDK 动态代理包装 WebServerApplicationContext + ConfigurableApplicationContext
        hints.proxies().registerJdkProxy(WebServerApplicationContext.class, ConfigurableApplicationContext.class);
        hints.reflection().registerType(PerfWebServer.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(PerfWebServerInitializedEvent.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);

        // ListenableFuture 异步返回路径：代理 + 回调反射（不依赖 classLoader）
        hints.proxies().registerJdkProxy(ListenableFutureCallback.class);
        hints.reflection().registerType(ListenableFutureCallback.class, MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerType(ListenableFuture.class, MemberCategory.INVOKE_PUBLIC_METHODS);

        // 资源探测需要 classLoader；契约允许 null，此时仅跳过这一组提示
        if (classLoader != null) {
            registerResourceHints(hints, classLoader);
        }
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

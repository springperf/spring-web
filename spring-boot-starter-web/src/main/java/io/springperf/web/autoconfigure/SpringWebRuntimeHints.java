package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.support.PerfWebServer;
import io.springperf.web.autoconfigure.support.PerfWebServerInitializedEvent;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.support.FilePatternResourceHintsRegistrar;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

/**
 * GraalVM native-image 可达性提示（由 {@code @ImportRuntimeHints} 在 Spring AOT 构建期采集， JVM 运行时完全不触发，零影响）。
 * <p>
 * 注册三类元数据：
 * <ul>
 * <li>JDK 动态代理：{@link PerfWebServerInitializedEvent} 用代理把真实上下文包装为
 * {@link WebServerApplicationContext}，native 下代理接口必须显式注册；</li>
 * <li>反射：事件路径要从代理调用与实例化 {@link PerfWebServer} / {@link PerfWebServerInitializedEvent}；</li>
 * <li>资源：框架强依赖的 classpath 资源（配置元数据、视图模板、静态资源目录）；</li>
 * <li>用户 {@code @Controller} 方法与 DTO 的反射/序列化提示由
 * {@link io.springperf.web.autoconfigure.support.ControllerBeanFactoryInitializationAotProcessor} 在 AOT 构建期按
 * BeanFactory 实际内容注册（本 registrar 不重复）。</li>
 * </ul>
 * <p>
 * 本分支（4.1.x）专用 Spring Boot 4，故直接引用 SB4 的类。早期为兼顾 SB3/SB4，
 * 这里曾把 SB4 类型名写成字符串再用 {@code registerTypeIfPresent} 按名条件注册，
 * 并额外声明若干 SB3 专属的提示——专用化后这些条件逻辑与字符串常量都已移除。
 * </p>
 */
public class SpringWebRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // 事件路径：JDK 动态代理包装 WebServerApplicationContext
        hints.proxies().registerJdkProxy(WebServerApplicationContext.class);
        hints.reflection().registerType(PerfWebServer.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
        hints.reflection().registerType(PerfWebServerInitializedEvent.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);

        // RuntimeHintsRegistrar 的契约允许 classLoader 为 null（上游标注 @Nullable），而资源注册依赖它做探测
        if (classLoader == null) {
            return;
        }

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

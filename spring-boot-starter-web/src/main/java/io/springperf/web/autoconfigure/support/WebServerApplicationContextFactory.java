package io.springperf.web.autoconfigure.support;

import org.springframework.aot.AotDetector;
import org.springframework.boot.ApplicationContextFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.lang.Nullable;

/**
 * 为基于 Netty 的 Web 应用选择注解驱动的应用上下文。
 * <p>
 * <b>为何需要全局 SPI</b>：本框架自行启动 Netty 服务器，不能使用 Boot 的
 * {@code AnnotationConfigServletWebServerApplicationContext} / {@code AnnotationConfigReactiveWebServerApplicationContext}
 * （它们会尝试创建 Servlet / Reactive 容器）。{@code spring.factories} 中的 {@link ApplicationContextFactory}
 * 是唯一能在 SpringApplication 创建上下文之前介入的扩展点，因此必须抢在 Boot 默认工厂之前
 * （{@code @Order(-10000)}）。
 * </p>
 * <p>
 * <b>条件化退让</b>：{@link #create(WebApplicationType)} 返回 {@code null} 表示「本工厂不适用」，
 * Boot {@code DefaultApplicationContextFactory} 会继续尝试其余工厂或回退到默认上下文。以下场景主动退让：
 * </p>
 * <ul>
 * <li>{@link WebApplicationType#NONE}：非 Web 应用无需替换上下文类型；</li>
 * <li>系统属性 {@code spring.perf.web.application-context-factory.enabled=false}：显式关闭本 SPI。
 * 也可以直接调用 {@code SpringApplication#setApplicationContextFactory} 覆盖（Boot 标准逃生口）。</li>
 * </ul>
 * <p>
 * <b>AOT / native-image</b>：{@link AotDetector#useGeneratedArtifacts()} 激活时返回
 * {@link GenericApplicationContext}，避免 {@code AnnotationConfigApplicationContext} 构造器反射实例化
 * {@code ConfigurationClassPostProcessor} 而缺少 native hints 崩溃。
 * </p>
 */
@Order(-10000)
public class WebServerApplicationContextFactory implements ApplicationContextFactory {

    /** 显式关闭本 SPI 的系统属性（与 {@code spring.aot.enabled} 的读取方式一致）。 */
    public static final String ENABLED_PROPERTY = "spring.perf.web.application-context-factory.enabled";

    @Override
    @Nullable
    public ConfigurableApplicationContext create(WebApplicationType webApplicationType) {
        if (!Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"))) {
            return null;
        }
        if (webApplicationType == WebApplicationType.NONE) {
            return null;
        }
        if (AotDetector.useGeneratedArtifacts()) {
            return new GenericApplicationContext();
        }
        return new AnnotationConfigApplicationContext();
    }
}

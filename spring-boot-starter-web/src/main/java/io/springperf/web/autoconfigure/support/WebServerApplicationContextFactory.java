package io.springperf.web.autoconfigure.support;

import org.springframework.aot.AotDetector;
import org.springframework.boot.ApplicationContextFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.Order;

/**
 * 强制使用注解驱动的应用上下文。
 *
 * <p>Spring Boot 3 AOT / GraalVM native-image 场景下，Spring Boot 官方用
 * {@link AotDetector#useGeneratedArtifacts()} 区分：AOT 激活时上下文为
 * {@link GenericApplicationContext}（AOT 生成的 {@code ApplicationContextInitializer}
 * 直接注册 bean 定义，无需运行时注解扫描）；否则为
 * {@link AnnotationConfigApplicationContext}。
 *
 * <p>若本工厂一律返回 {@code AnnotationConfigApplicationContext}，其构造器会经
 * {@code AnnotatedBeanDefinitionReader} 注册 {@code ConfigurationClassPostProcessor}，
 * refresh 时通过反射实例化该处理器——native-image 下无构造器 hints 将崩溃
 * （{@code NoSuchMethodException: ...ConfigurationClassPostProcessor.<init>()}）。
 * 故必须与 Spring Boot 官方行为对齐：AOT/native 用 {@link GenericApplicationContext}。
 */
@Order(-10000)
public class WebServerApplicationContextFactory implements ApplicationContextFactory {

    @Override
    public ConfigurableApplicationContext create(WebApplicationType webApplicationType) {
        if (AotDetector.useGeneratedArtifacts()) {
            return new GenericApplicationContext();
        }
        return new AnnotationConfigApplicationContext();
    }
}

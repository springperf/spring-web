package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.support.WebServerApplicationContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

class WebServerApplicationContextFactoryTest {

    private final WebServerApplicationContextFactory factory = new WebServerApplicationContextFactory();

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty("spring.aot.enabled");
        System.clearProperty(WebServerApplicationContextFactory.ENABLED_PROPERTY);
    }

    @Test
    void create_returnsAnnotationConfigApplicationContext() {
        try (ConfigurableApplicationContext ctx = factory.create(WebApplicationType.SERVLET)) {
            assertNotNull(ctx);
            assertInstanceOf(AnnotationConfigApplicationContext.class, ctx);
        }
    }

    @Test
    void create_withNullType_returnsContext() {
        try (ConfigurableApplicationContext ctx = factory.create(null)) {
            assertNotNull(ctx);
            assertInstanceOf(AnnotationConfigApplicationContext.class, ctx);
        }
    }

    @Test
    void create_withReactiveType_returnsContext() {
        try (ConfigurableApplicationContext ctx = factory.create(WebApplicationType.REACTIVE)) {
            assertNotNull(ctx);
            assertInstanceOf(AnnotationConfigApplicationContext.class, ctx);
        }
    }

    @Test
    void create_withNoneType_defersToBootDefault() {
        // 非 Web 应用不需要替换上下文类型；返回 null 让 DefaultApplicationContextFactory 继续尝试其它工厂
        assertNull(factory.create(WebApplicationType.NONE));
    }

    @Test
    void create_whenDisabledBySystemProperty_defersToBootDefault() {
        System.setProperty(WebServerApplicationContextFactory.ENABLED_PROPERTY, "false");

        assertNull(factory.create(WebApplicationType.SERVLET));
        assertNull(factory.create(WebApplicationType.REACTIVE));
    }

    @Test
    void create_inAotMode_returnsGenericApplicationContext() {
        // 模拟 Spring Boot AOT / native-image：AotDetector.useGeneratedArtifacts() 依赖 spring.aot.enabled
        System.setProperty("spring.aot.enabled", "true");
        try (ConfigurableApplicationContext ctx = factory.create(WebApplicationType.SERVLET)) {
            assertNotNull(ctx);
            assertInstanceOf(GenericApplicationContext.class, ctx);
            assertFalse(ctx instanceof AnnotationConfigApplicationContext,
                    "AOT 模式不应返回注解驱动上下文（避免 ConfigurationClassPostProcessor 反射实例化）");
        }
    }

    @Test
    void create_inAotMode_whenDisabled_stillDefers() {
        System.setProperty("spring.aot.enabled", "true");
        System.setProperty(WebServerApplicationContextFactory.ENABLED_PROPERTY, "false");

        assertNull(factory.create(WebApplicationType.SERVLET), "关闭开关应优先于 AOT 分支");
    }

    @Test
    void create_returnsNewInstanceEachCall() {
        try (ConfigurableApplicationContext ctx1 = factory.create(null);
                ConfigurableApplicationContext ctx2 = factory.create(null)) {
            assertNotSame(ctx1, ctx2);
        }
    }
}

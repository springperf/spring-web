package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.support.WebServerApplicationContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

class WebServerApplicationContextFactoryTest {

    private final WebServerApplicationContextFactory factory = new WebServerApplicationContextFactory();

    @AfterEach
    void clearSystemProperty() {
        System.clearProperty("spring.aot.enabled");
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
    void create_withNoneType_returnsContext() {
        try (ConfigurableApplicationContext ctx = factory.create(WebApplicationType.NONE)) {
            assertNotNull(ctx);
            assertInstanceOf(AnnotationConfigApplicationContext.class, ctx);
        }
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
    void create_returnsNewInstanceEachCall() {
        try (ConfigurableApplicationContext ctx1 = factory.create(null);
             ConfigurableApplicationContext ctx2 = factory.create(null)) {
            assertNotSame(ctx1, ctx2);
        }
    }
}
package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.support.WebServerApplicationContextFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

class WebServerApplicationContextFactoryTest {

    private final WebServerApplicationContextFactory factory = new WebServerApplicationContextFactory();

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
    void create_returnsNewInstanceEachCall() {
        try (ConfigurableApplicationContext ctx1 = factory.create(null);
             ConfigurableApplicationContext ctx2 = factory.create(null)) {
            assertNotSame(ctx1, ctx2);
        }
    }
}
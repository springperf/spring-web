package io.springperf.web.support.mvc.interceptor;

import io.springperf.web.core.interceptor.InterceptorRegistration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.Order;
import org.springframework.util.PathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.MappedInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class SupportInterceptorRegistryTest {

    @Mock
    PathMatcher pathMatcher;

    @Test
    void convertInterceptorRegistration_copiesOrder() {
        org.springframework.web.servlet.config.annotation.InterceptorRegistration reg =
                new org.springframework.web.servlet.config.annotation.InterceptorRegistration(
                        new TestHandlerInterceptor());
        reg.order(5);

        SupportInterceptorRegistry registry = new SupportInterceptorRegistry();
        InterceptorRegistration result = registry.convert(reg);

        assertEquals(5, result.getOrder());
    }

    @Test
    void convertInterceptorRegistration_defaultOrderIsZero() {
        org.springframework.web.servlet.config.annotation.InterceptorRegistration reg =
                new org.springframework.web.servlet.config.annotation.InterceptorRegistration(
                        new TestHandlerInterceptor());

        SupportInterceptorRegistry registry = new SupportInterceptorRegistry();
        InterceptorRegistration result = registry.convert(reg);

        assertEquals(0, result.getOrder());
    }

    @Test
    void convertInterceptorRegistration_wrapsInterceptor() {
        TestHandlerInterceptor interceptor = new TestHandlerInterceptor();
        org.springframework.web.servlet.config.annotation.InterceptorRegistration reg =
                new org.springframework.web.servlet.config.annotation.InterceptorRegistration(interceptor);

        SupportInterceptorRegistry registry = new SupportInterceptorRegistry();
        InterceptorRegistration result = registry.convert(reg);

        assertNotNull(result);
    }

    @Test
    void convertPlainHandlerInterceptor_wrapsAndMaintainsOrder() {
        OrderedInterceptor interceptor = new OrderedInterceptor();

        SupportInterceptorRegistry registry = new SupportInterceptorRegistry();
        InterceptorRegistration result = registry.convert((HandlerInterceptor) interceptor);

        assertEquals(42, result.getOrder());
    }

    @Test
    void convertPlainHandlerInterceptor_withoutOrder_defaultZero() {
        TestHandlerInterceptor interceptor = new TestHandlerInterceptor();

        SupportInterceptorRegistry registry = new SupportInterceptorRegistry();
        InterceptorRegistration result = registry.convert((HandlerInterceptor) interceptor);

        assertEquals(0, result.getOrder());
    }

    @Test
    void convertMappedInterceptor_createsRegistration() {
        TestHandlerInterceptor inner = new TestHandlerInterceptor();
        MappedInterceptor mapped = new MappedInterceptor(
                new String[]{"/api/**"}, new String[]{"/api/public/**"}, inner);

        SupportInterceptorRegistry registry = new SupportInterceptorRegistry();
        InterceptorRegistration result = registry.convert((HandlerInterceptor) mapped);

        assertNotNull(result);
        // include/exclude 路径应从 MappedInterceptor 复制到注册项
        assertEquals(java.util.List.of("/api/**"), readList(result, "includePatterns"));
        assertEquals(java.util.List.of("/api/public/**"), readList(result, "excludePatterns"));
    }

    @Test
    void convertMappedInterceptor_withoutExcludes() {
        TestHandlerInterceptor inner = new TestHandlerInterceptor();
        MappedInterceptor mapped = new MappedInterceptor(new String[]{"/secure/*"}, new String[0], inner);

        SupportInterceptorRegistry registry = new SupportInterceptorRegistry();
        InterceptorRegistration result = registry.convert((HandlerInterceptor) mapped);

        assertNotNull(result);
        assertEquals(java.util.List.of("/secure/*"), readList(result, "includePatterns"));
        assertTrue(readList(result, "excludePatterns").isEmpty(), "无 exclude 时不应产生排除路径");
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> readList(InterceptorRegistration registration, String fieldName) {
        try {
            java.lang.reflect.Field field = InterceptorRegistration.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (java.util.List<String>) field.get(registration);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void constructor_doesNotThrow() {
        assertDoesNotThrow(SupportInterceptorRegistry::new);
    }

    // ---- Helper classes ----

    static class TestHandlerInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            return true;
        }
    }

    @Order(42)
    static class OrderedInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            return true;
        }
    }
}

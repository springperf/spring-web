package io.springperf.web.support.mvc.interceptor;

import io.springperf.web.core.interceptor.InterceptorRegistration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.Order;
import org.springframework.util.PathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.MappedInterceptor;

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
    }

    @Test
    void convertMappedInterceptor_withoutExcludes() {
        TestHandlerInterceptor inner = new TestHandlerInterceptor();
        MappedInterceptor mapped = new MappedInterceptor(new String[]{"/secure/*"}, new String[0], inner);

        SupportInterceptorRegistry registry = new SupportInterceptorRegistry();
        InterceptorRegistration result = registry.convert((HandlerInterceptor) mapped);

        assertNotNull(result);
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

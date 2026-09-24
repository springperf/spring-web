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

import io.springperf.web.context.WebContext;
import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportInterceptorRegistryTest {

    @Mock
    PathMatcher pathMatcher;

    @Test
    void convertInterceptorRegistration_copiesOrder() {
        org.springframework.web.servlet.config.annotation.InterceptorRegistration reg = new org.springframework.web.servlet.config.annotation.InterceptorRegistration(
                new TestHandlerInterceptor());
        reg.order(5);

        SupportInterceptorRegistry registry = new SupportInterceptorRegistry();
        InterceptorRegistration result = registry.convert(reg);

        assertEquals(5, result.getOrder());
    }

    @Test
    void convertInterceptorRegistration_defaultOrderIsZero() {
        org.springframework.web.servlet.config.annotation.InterceptorRegistration reg = new org.springframework.web.servlet.config.annotation.InterceptorRegistration(
                new TestHandlerInterceptor());

        SupportInterceptorRegistry registry = new SupportInterceptorRegistry();
        InterceptorRegistration result = registry.convert(reg);

        assertEquals(0, result.getOrder());
    }

    @Test
    void convertInterceptorRegistration_wrapsInterceptor() {
        TestHandlerInterceptor interceptor = new TestHandlerInterceptor();
        org.springframework.web.servlet.config.annotation.InterceptorRegistration reg = new org.springframework.web.servlet.config.annotation.InterceptorRegistration(
                interceptor);

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
        MappedInterceptor mapped = new MappedInterceptor(new String[] { "/api/**" }, new String[] { "/api/public/**" },
                inner);

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
        MappedInterceptor mapped = new MappedInterceptor(new String[] { "/secure/*" }, new String[0], inner);

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

    // ---- 自动注册语义（修复 C4）----

    @Test
    void plainHandlerInterceptorBean_isNotAutoRegistered() throws Exception {
        // 修复前：普通 org.springframework.web.servlet.HandlerInterceptor bean 会被无条件全局注册
        // （偏离 Spring 语义，且与 WebMvcConfigurer.addInterceptors 桥接的同一实例同名碰撞/重复执行）。
        WebContext webContext = mock(WebContext.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(webContext.getCtx()).thenReturn(ctx);
        // 模拟用户注册了一个 Spring 兼容型 HandlerInterceptor bean（修复后应被忽略，不自动注册）
        lenient().when(ctx.getBeansOfType(org.springframework.web.servlet.HandlerInterceptor.class))
                .thenReturn(Collections.singletonMap("spring", new TestHandlerInterceptor()));
        when(ctx.getBeansOfType(io.springperf.web.core.interceptor.HandlerInterceptor.class))
                .thenReturn(Collections.emptyMap());
        // InterceptorRegistry 父构造自动注册 io.springperf.web.core.interceptor.InterceptorRegistration，
        // initWithWebContext 会查询 getBeansOfType，需 stub（否则严格 stubbing 误报参数不匹配）
        when(ctx.getBeansOfType(io.springperf.web.core.interceptor.InterceptorRegistration.class))
                .thenReturn(Collections.emptyMap());
        when(ctx.getBeansOfType(org.springframework.web.servlet.handler.MappedInterceptor.class))
                .thenReturn(Collections.emptyMap());
        when(ctx.getBeansOfType(org.springframework.web.servlet.config.annotation.InterceptorRegistration.class))
                .thenReturn(Collections.emptyMap());

        SupportInterceptorRegistry registry = new SupportInterceptorRegistry();
        registry.initWithWebContext(webContext);

        assertTrue(registry.getWebComponents(InterceptorRegistration.class).isEmpty(),
                "普通 org.springframework.web.servlet.HandlerInterceptor bean 不应被自动全局注册（需经 addInterceptors 或 MappedInterceptor）");
    }

    @Test
    void mappedInterceptorBean_isAutoRegisteredWithPatterns() throws Exception {
        // 回归：MappedInterceptor bean（Spring 自动探测类型）仍应自动注册并保留其路径规则
        WebContext webContext = mock(WebContext.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(webContext.getCtx()).thenReturn(ctx);
        when(ctx.getBeansOfType(io.springperf.web.core.interceptor.HandlerInterceptor.class))
                .thenReturn(Collections.emptyMap());
        when(ctx.getBeansOfType(io.springperf.web.core.interceptor.InterceptorRegistration.class))
                .thenReturn(Collections.emptyMap());
        // 使用 3 参构造器（excludes 显式为空数组），与既有测试约定一致，避免 getExcludePatterns() 为 null
        MappedInterceptor mapped = new MappedInterceptor(new String[] { "/api/**" }, new String[0],
                new TestHandlerInterceptor());
        when(ctx.getBeansOfType(org.springframework.web.servlet.handler.MappedInterceptor.class))
                .thenReturn(Collections.singletonMap("mapped", mapped));
        when(ctx.getBeansOfType(org.springframework.web.servlet.config.annotation.InterceptorRegistration.class))
                .thenReturn(Collections.emptyMap());

        SupportInterceptorRegistry registry = new SupportInterceptorRegistry();
        registry.initWithWebContext(webContext);

        List<InterceptorRegistration> regs = registry.getWebComponents(InterceptorRegistration.class);
        assertEquals(1, regs.size(), "MappedInterceptor bean 应被自动注册");
        assertEquals(List.of("/api/**"), readList(regs.get(0), "includePatterns"),
                "MappedInterceptor 的 include 路径应被保留");
    }

    @Test
    void convertMappedInterceptor_twoArgConstructor_doesNotThrowOnNullExcludePatterns() {
        // 修复 H1：标准两参构造器 new MappedInterceptor(includes, interceptor) 的
        // getExcludePatterns() 返回 null，修复前 convert 直接传给 excludePathPatterns(String...)
        // 触发 Arrays.asList(null) NPE，导致该 bean 自动注册时启动崩溃。
        MappedInterceptor mapped = new MappedInterceptor(new String[] { "/api/**" }, new TestHandlerInterceptor());
        SupportInterceptorRegistry registry = new SupportInterceptorRegistry();

        InterceptorRegistration result = registry.convert((HandlerInterceptor) mapped);

        assertNotNull(result, "两参 MappedInterceptor 不应因 null excludePatterns 而 NPE");
        assertEquals(List.of("/api/**"), readList(result, "includePatterns"), "两参 MappedInterceptor 的 include 路径应被保留");
    }
}

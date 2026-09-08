package io.springperf.web.core.arg.provider;

import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 {@link LocaleResolverProvider}：
 * 优先尊重线程已绑定的 Locale（拦截器 / ControllerAdvice setLocale 覆盖），
 * 未绑定时按请求 Accept-Language 解析，且不写 ThreadLocal（零固定开销）。
 */
@ExtendWith(MockitoExtension.class)
class LocaleResolverProviderTest {

    @Mock WebServerHttpRequest request;
    @Mock WebServerHttpResponse response;
    @Mock MappingHandlerMethod mappingContext;

    private final LocaleResolverProvider provider = new LocaleResolverProvider();

    @SuppressWarnings("unused")
    public void localeParam(Locale locale) {
    }

    @SuppressWarnings("unused")
    public void stringParam(String value) {
    }

    private MethodParameter param(String methodName, int index) throws Exception {
        for (Method m : getClass().getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                return new MethodParameter(m, index);
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    @Test
    void supports_localeType_returnsTrue() throws Exception {
        assertTrue(provider.supports(param("localeParam", 0), mappingContext));
    }

    @Test
    void supports_nonLocaleType_returnsFalse() throws Exception {
        assertFalse(provider.supports(param("stringParam", 0), mappingContext));
    }

    @Test
    void getResolver_returnsResolver() throws Exception {
        StaticArgumentResolver resolver = provider.getResolver(param("localeParam", 0), mappingContext, null);
        assertNotNull(resolver);
    }

    @Test
    void resolveArgument_noThreadBinding_usesRequestLocale() throws Exception {
        // 未绑定线程 locale：按请求 Accept-Language 解析
        when(request.getLocale()).thenReturn(Locale.SIMPLIFIED_CHINESE);
        StaticArgumentResolver resolver = provider.getResolver(param("localeParam", 0), mappingContext, null);

        assertEquals(Locale.SIMPLIFIED_CHINESE, resolver.resolveArgument(request, response));
    }

    @Test
    void resolveArgument_threadBinding_takesPrecedence() throws Exception {
        // 线程已绑定（如拦截器 setLocale）：即使请求 Accept-Language 不同，也尊重线程值
        LocaleContextHolder.setLocale(Locale.UK);
        try {
            StaticArgumentResolver resolver = provider.getResolver(param("localeParam", 0), mappingContext, null);
            assertEquals(Locale.UK, resolver.resolveArgument(request, response),
                    "线程已绑定 Locale 时解析应尊重覆盖值");
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void resolveArgument_doesNotWriteThreadLocal() throws Exception {
        // 零固定开销：解析不应写 ThreadLocal（避免线程池残留）
        when(request.getLocale()).thenReturn(Locale.FRENCH);
        StaticArgumentResolver resolver = provider.getResolver(param("localeParam", 0), mappingContext, null);

        resolver.resolveArgument(request, response);

        assertNull(LocaleContextHolder.getLocaleContext(),
                "解析不应写入 LocaleContextHolder");
    }

    @Test
    void resolveArgument_optionalLocale_wrapsInOptional() throws Exception {
        java.util.Optional<Locale> opt = java.util.Optional.of(Locale.FRENCH);
        @SuppressWarnings("unused")
        class Holder {
            void m(java.util.Optional<Locale> o) {
            }
        }
        java.lang.reflect.Method m = Holder.class.getDeclaredMethods()[0];
        MethodParameter optionalParam = new MethodParameter(m, 0);
        StaticArgumentResolver resolver = provider.getResolver(optionalParam, mappingContext, null);
        when(request.getLocale()).thenReturn(Locale.FRENCH);

        Object resolved = resolver.resolveArgument(request, response);

        assertEquals(opt, resolved);
    }

    @Test
    void getResolver_ignoresWebContext() throws Exception {
        StaticArgumentResolver resolver = provider.getResolver(param("localeParam", 0), mappingContext,
                mock(io.springperf.web.context.WebContext.class));
        assertNotNull(resolver);
    }
}
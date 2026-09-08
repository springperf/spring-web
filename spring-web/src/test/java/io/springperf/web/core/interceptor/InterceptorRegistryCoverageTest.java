package io.springperf.web.core.interceptor;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.util.PathMatcher;
import org.springframework.web.bind.annotation.ControllerAdvice;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InterceptorRegistryCoverageTest {

    @ControllerAdvice
    static class AdviceInterceptor implements HandlerInterceptor {
    }

    static class PlainInterceptor implements HandlerInterceptor {
    }

    @Test
    void lifecycle_initAndPhase2_reloadsRegistrationsAndRuntimeInterceptors() throws Exception {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        try {
            ctx.registerBean("advice", AdviceInterceptor.class);
            ctx.registerBean("plain", PlainInterceptor.class);
            ctx.refresh();

            WebContext webContext = mock(WebContext.class);
            when(webContext.getCtx()).thenReturn(ctx);

            InterceptorRegistry registry = new InterceptorRegistry();
            registry.initWithWebContext(webContext);
            registry.initComponentPhase1();
            registry.initComponentPhase2();

            List<InterceptorRegistration> registrations = registrations(registry);
            assertEquals(2, registrations.size());
            InterceptorRegistration adviceRegistration = registrations.stream()
                    .filter(InterceptorRegistration::isControllerAdviceScoped)
                    .findFirst().orElseThrow(AssertionError::new);
            assertTrue(adviceRegistration.matchesControllerType(AdviceInterceptor.class));
            assertTrue(adviceRegistration.matchesControllerType(PlainInterceptor.class));

            List<HandlerInterceptor> runtimeInterceptors = runtimeInterceptors(registry);
            assertEquals(1, runtimeInterceptors.size());
            assertTrue(runtimeInterceptors.get(0) instanceof PlainInterceptor);
        } finally {
            ctx.close();
        }
    }

    @Test
    void initCachedInterceptors_runtimeContainment_wrapsInRuntimeInterceptor() {
        InterceptorRegistry registry = new InterceptorRegistry();
        HandlerInterceptor handler = new HandlerInterceptor() {
        };
        registry.registerInterceptor(handler).addPathPatterns("/api/*");

        PathMappingContext mappingContext = mock(PathMappingContext.class);
        when(mappingContext.getPathRule()).thenReturn("/api/**");

        List<HandlerInterceptor> interceptors = registry.initCachedInterceptors(mappingContext);

        assertEquals(1, interceptors.size());
        assertTrue(interceptors.get(0) instanceof RuntimeMappingInterceptor);
        RuntimeMappingInterceptor wrapper = (RuntimeMappingInterceptor) interceptors.get(0);
        assertSame(handler, wrapper.getInterceptor());
        assertNull(wrapper.getPathMatcher());
    }

    @Test
    void getRuntimeMappingInterceptor_withCustomPathMatcher_passThroughMatcher() {
        InterceptorRegistry registry = new InterceptorRegistry();
        HandlerInterceptor handler = new HandlerInterceptor() {
        };
        PathMatcher pathMatcher = mock(PathMatcher.class);
        InterceptorRegistration registration = registry.registerInterceptor(handler)
                .addPathPatterns("/api/*")
                .pathMatcher(pathMatcher);

        HandlerInterceptor result = registry.getRuntimeMappingInterceptor(registration);

        assertTrue(result instanceof RuntimeMappingInterceptor);
        assertSame(pathMatcher, ((RuntimeMappingInterceptor) result).getPathMatcher());
        assertSame(handler, ((RuntimeMappingInterceptor) result).getInterceptor());
    }

    @Test
    void realGetInterceptors_emptyRuntimeList_returnsEmpty() {
        InterceptorRegistry registry = new InterceptorRegistry();
        WebServerHttpRequest request = requestWithContextStorage();
        PathMappingContext mappingContext = mock(PathMappingContext.class);
        when(mappingContext.getPathRule()).thenReturn("/api/**");
        when(mappingContext.getCachedInterceptors()).thenReturn(null);
        MappingResult.set(request, MappingResult.matched(mappingContext));

        List<HandlerInterceptor> interceptors = registry.realGetInterceptors(request);

        assertTrue(interceptors.isEmpty());
        verify(mappingContext).setCachedInterceptors(interceptors);
    }

    @Test
    void getRuntimeInterceptors_mixedList_resolvesRuntimeAndKeepsPlain() {
        InterceptorRegistry registry = new InterceptorRegistry();
        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        when(request.getPath()).thenReturn("/api/users");
        HandlerInterceptor inner = mock(HandlerInterceptor.class);
        HandlerInterceptor plain = mock(HandlerInterceptor.class);
        List<HandlerInterceptor> input = Arrays.asList(
                new RuntimeMappingInterceptor(new String[]{"/api/*"}, new String[0], inner),
                plain);

        List<HandlerInterceptor> result = registry.getRuntimeInterceptors(request, input);

        assertEquals(2, result.size());
        assertSame(inner, result.get(0));
        assertSame(plain, result.get(1));
    }

    @Test
    void preHandle_afterCompletionOfPassedThrows_isSwallowed() throws Exception {
        InterceptorRegistry registry = new InterceptorRegistry();
        HandlerInterceptor i1 = mock(HandlerInterceptor.class);
        HandlerInterceptor i2 = mock(HandlerInterceptor.class);
        when(i1.preHandle(any(), any(), any())).thenReturn(true);
        when(i2.preHandle(any(), any(), any())).thenReturn(false);
        doThrow(new RuntimeException("after boom")).when(i1).afterCompletion(any(), any(), any(), any());

        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        RequestContext requestContext = mock(RequestContext.class);
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(Arrays.asList(i1, i2));

        assertFalse(registry.preHandle(request, response));
        verify(i1).afterCompletion(any(), any(), any(), isNull());
        verify(i2, never()).afterCompletion(any(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private static List<InterceptorRegistration> registrations(InterceptorRegistry registry) throws Exception {
        Field field = InterceptorRegistry.class.getDeclaredField("registrations");
        field.setAccessible(true);
        return (List<InterceptorRegistration>) field.get(registry);
    }

    @SuppressWarnings("unchecked")
    private static List<HandlerInterceptor> runtimeInterceptors(InterceptorRegistry registry) throws Exception {
        Field field = InterceptorRegistry.class.getDeclaredField("runtimeMappingInterceptors");
        field.setAccessible(true);
        return (List<HandlerInterceptor>) field.get(registry);
    }

    private static WebServerHttpRequest requestWithContextStorage() {
        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        RequestContext requestContext = mock(RequestContext.class);
        when(request.getRequestContext()).thenReturn(requestContext);
        Map<RequestAttribute<?>, Object> store = new HashMap<>();
        doAnswer(invocation -> {
            requestStoreInvocation(store, invocation);
            return null;
        }).when(requestContext).setAttribute(any(RequestAttribute.class), any());
        when(requestContext.getAttribute(any(RequestAttribute.class)))
                .thenAnswer(invocation -> store.get(invocation.getArgument(0)));
        return request;
    }

    private static void requestStoreInvocation(Map<RequestAttribute<?>, Object> store,
                                               org.mockito.invocation.InvocationOnMock invocation) {
        store.put(invocation.getArgument(0), invocation.getArgument(1));
    }
}
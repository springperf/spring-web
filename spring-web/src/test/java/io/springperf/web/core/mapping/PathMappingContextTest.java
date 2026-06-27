package io.springperf.web.core.mapping;

import io.springperf.web.core.cors.provider.CorsConfigurationProvider;
import io.springperf.web.core.interceptor.HandlerInterceptor;
import io.springperf.web.core.invoker.CustomInvoker;
import io.springperf.web.core.mapping.match.ConsumeOrProduceMatcher;
import io.springperf.web.core.mapping.match.Matcher;
import io.springperf.web.core.mapping.match.MediaTypeExpressionSupport;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PathMappingContextTest {

    static class TestController {
        @RequestMapping("/test")
        public String hello() { return "hello"; }
    }

    private HandlerMethod createHandlerMethod() throws NoSuchMethodException {
        return new HandlerMethod(new TestController(), TestController.class.getMethod("hello"));
    }

    @Test
    void constructor_withHandlerMethod_setsBasicFields() throws Exception {
        HandlerMethod hm = createHandlerMethod();
        PathMappingContext ctx = new PathMappingContext(hm, Collections.emptyList(), "/api/test");

        assertEquals("/api/test", ctx.getPathRule());
        assertNotNull(ctx.getMatchers());
        assertEquals(0, ctx.getMatchers().length);
    }

    @Test
    void constructor_withHandlerMethodAndMatchers_setsMatchers() throws Exception {
        HandlerMethod hm = createHandlerMethod();
        Matcher matcher = mock(Matcher.class);
        List<Matcher> matchers = Collections.singletonList(matcher);

        PathMappingContext ctx = new PathMappingContext(hm, matchers, "/api/test");

        assertEquals(1, ctx.getMatchers().length);
        assertSame(matcher, ctx.getMatchers()[0]);
    }

    @Test
    void constructor_withCustomInvoker_setsFields() throws Exception {
        Method method = TestController.class.getMethod("hello");
        CustomInvoker invoker = mock(CustomInvoker.class);
        when(invoker.getHandleMethod()).thenReturn(method);
        when(invoker.getMatchers()).thenReturn(Collections.emptyList());
        when(invoker.getType()).thenReturn("Custom");

        PathMappingContext ctx = new PathMappingContext(invoker, "/api/custom");

        assertEquals("/api/custom", ctx.getPathRule());
        assertNotNull(ctx.getMatchers());
        assertTrue(ctx.toString().startsWith("Custom:"));
    }

    @Test
    void initProducibleMediaTypes_withConsumeMatcher_returnsNull() throws Exception {
        HandlerMethod hm = createHandlerMethod();
        ConsumeOrProduceMatcher consumeMatcher = new ConsumeOrProduceMatcher(false,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json")));

        PathMappingContext ctx = new PathMappingContext(hm,
                Collections.singletonList(consumeMatcher), "/api/test");

        assertNull(ctx.getProducibleMediaTypes());
    }

    @Test
    void initProducibleMediaTypes_withProduceMatcher_returnsTypes() throws Exception {
        HandlerMethod hm = createHandlerMethod();
        ConsumeOrProduceMatcher produceMatcher = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json")));

        PathMappingContext ctx = new PathMappingContext(hm,
                Collections.singletonList(produceMatcher), "/api/test");

        assertNotNull(ctx.getProducibleMediaTypes());
        assertTrue(ctx.getProducibleMediaTypes().contains(MediaType.APPLICATION_JSON));
    }

    @Test
    void initProducibleMediaTypes_withMultipleMatchers_findsFirstProduce() throws Exception {
        HandlerMethod hm = createHandlerMethod();
        Matcher httpMethodMatcher = mock(Matcher.class);
        ConsumeOrProduceMatcher produceMatcher = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("text/html")));

        PathMappingContext ctx = new PathMappingContext(hm,
                Arrays.asList(httpMethodMatcher, produceMatcher), "/api/test");

        assertNotNull(ctx.getProducibleMediaTypes());
        assertTrue(ctx.getProducibleMediaTypes().contains(MediaType.TEXT_HTML));
    }

    @Test
    void initProducibleMediaTypes_withoutConsumeOrProduce_returnsNull() throws Exception {
        HandlerMethod hm = createHandlerMethod();
        Matcher matcher = mock(Matcher.class);

        PathMappingContext ctx = new PathMappingContext(hm,
                Collections.singletonList(matcher), "/api/test");

        assertNull(ctx.getProducibleMediaTypes());
    }

    @Test
    void interceptors_getAndSet() throws Exception {
        HandlerMethod hm = createHandlerMethod();
        PathMappingContext ctx = new PathMappingContext(hm, Collections.emptyList(), "/api/test");

        assertNull(ctx.getCachedInterceptors());

        List<HandlerInterceptor> interceptors = Collections.emptyList();
        ctx.setCachedInterceptors(interceptors);
        assertSame(interceptors, ctx.getCachedInterceptors());
    }

    @Test
    void corsProvider_getAndSet() throws Exception {
        HandlerMethod hm = createHandlerMethod();
        PathMappingContext ctx = new PathMappingContext(hm, Collections.emptyList(), "/api/test");

        assertNull(ctx.getCorsConfigurationProvider());

        CorsConfigurationProvider provider = mock(
                CorsConfigurationProvider.class);
        ctx.setCorsConfigurationProvider(provider);
        assertSame(provider, ctx.getCorsConfigurationProvider());
    }

    @Test
    void toString_withoutMatchers_returnsTypeAndPath() throws Exception {
        HandlerMethod hm = createHandlerMethod();
        PathMappingContext ctx = new PathMappingContext(hm, Collections.emptyList(), "/api/test");

        assertEquals("Controller:/api/test", ctx.toString());
    }

    @Test
    void toString_withMatchers_includesMatchers() throws Exception {
        HandlerMethod hm = createHandlerMethod();
        Matcher matcher = mock(Matcher.class);
        when(matcher.toString()).thenReturn("GET");

        PathMappingContext ctx = new PathMappingContext(hm,
                Collections.singletonList(matcher), "/api/test");

        assertTrue(ctx.toString().startsWith("Controller:/api/test "));
    }

    @Test
    void staticGet_returnsNull_whenNoMappingResult() {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        RequestContext reqCtx = mock(RequestContext.class);
        when(req.getRequestContext()).thenReturn(reqCtx);

        assertNull(PathMappingContext.get(req));
    }

    @Test
    void staticGet_delegatesToMappingResult_whenMatched() {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        RequestContext reqCtx = mock(RequestContext.class, withSettings().defaultAnswer(invocation -> null));
        when(req.getRequestContext()).thenReturn(reqCtx);

        // 模拟 fastAttributes 以支持 RequestAttribute 存取
        Map<Integer, Object> fastAttrs = new HashMap<>();
        when(reqCtx.getAttribute(any(RequestAttribute.class))).thenAnswer(invocation -> {
            RequestAttribute<?> attr = invocation.getArgument(0);
            return fastAttrs.get(attr.getIndex());
        });
        doAnswer(invocation -> {
            RequestAttribute<?> attr = invocation.getArgument(0);
            Object value = invocation.getArgument(1);
            fastAttrs.put(attr.getIndex(), value);
            return null;
        }).when(reqCtx).setAttribute(any(RequestAttribute.class), any());

        PathMappingContext ctx = mock(PathMappingContext.class);
        MappingResult matched = MappingResult.matched(ctx);
        MappingResult.set(req, matched);

        assertSame(ctx, PathMappingContext.get(req));
    }
}
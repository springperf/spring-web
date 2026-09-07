package io.springperf.web.core.mapping.optimize;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.route.Router;
import io.springperf.web.core.mapping.route.SimpleRouter;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoopPathPatternRouterOptimizerTest {

    private static final String ATTR_STORE_KEY = "_attr_";

    private WebServerHttpRequest mockRequest(String path) {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getPath()).thenReturn(path);
        RequestContext reqCtx = mock(RequestContext.class);
        when(req.getRequestContext()).thenReturn(reqCtx);
        Map<String, Object> attrs = new HashMap<>();
        when(reqCtx.getAttribute(any(String.class))).thenAnswer(inv -> attrs.get(inv.getArgument(0)));
        when(reqCtx.getAttribute(any(RequestAttribute.class))).thenAnswer(inv ->
                attrs.get(ATTR_STORE_KEY + ((RequestAttribute) inv.getArgument(0)).getIndex()));
        doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(reqCtx).setAttribute(any(String.class), any());
        doAnswer(inv -> {
            RequestAttribute<?> ra = inv.getArgument(0);
            attrs.put(ATTR_STORE_KEY + ra.getIndex(), inv.getArgument(1));
            return null;
        }).when(reqCtx).setAttribute(any(RequestAttribute.class), any());
        return req;
    }

    @Test
    void initAndRemove_addsContextAndReturnsTrue() {
        LoopPathPatternRouterOptimizer optimizer = new LoopPathPatternRouterOptimizer();
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getPathRule()).thenReturn("/api/{id}");
        when(ctx.getMatchers()).thenReturn(new io.springperf.web.core.mapping.match.Matcher[0]);

        assertTrue(optimizer.initAndRemove(ctx));
    }

    @Test
    void initAndRemove_addMultipleContexts() {
        LoopPathPatternRouterOptimizer optimizer = new LoopPathPatternRouterOptimizer();
        PathMappingContext ctx1 = mock(PathMappingContext.class);
        when(ctx1.getPathRule()).thenReturn("/api/{id}");
        when(ctx1.getMatchers()).thenReturn(new io.springperf.web.core.mapping.match.Matcher[0]);
        PathMappingContext ctx2 = mock(PathMappingContext.class);
        when(ctx2.getPathRule()).thenReturn("/api/{version}");
        when(ctx2.getMatchers()).thenReturn(new io.springperf.web.core.mapping.match.Matcher[0]);

        assertTrue(optimizer.initAndRemove(ctx1));
        assertTrue(optimizer.initAndRemove(ctx2));
    }

    @Test
    void optimizeRoute_returnsInnerRouter() {
        LoopPathPatternRouterOptimizer optimizer = new LoopPathPatternRouterOptimizer();
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getPathRule()).thenReturn("/api/{id}");
        when(ctx.getMatchers()).thenReturn(new io.springperf.web.core.mapping.match.Matcher[0]);
        optimizer.initAndRemove(ctx);

        WebServerHttpRequest req = mockRequest("/api/123");
        Router router = optimizer.optimizeRoute(req);
        assertNotNull(router);
        assertSame(ctx, router.route(req), "路由应命中注册的 PathMappingContext");
    }

    @Test
    void optimizeRoute_noContext_returnsEmptyRouter() {
        LoopPathPatternRouterOptimizer optimizer = new LoopPathPatternRouterOptimizer();
        WebServerHttpRequest req = mockRequest("/api/123");
        Router router = optimizer.optimizeRoute(req);
        assertNotNull(router);
        assertNull(router.route(req), "无上下文时路由不应命中任何映射");
    }
}
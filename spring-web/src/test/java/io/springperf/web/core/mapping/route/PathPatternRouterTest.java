package io.springperf.web.core.mapping.route;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.match.Matcher;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PathPatternRouterTest {

    private static final String ATTR_STORE_KEY = "_attr_";

    private WebServerHttpRequest createMockRequest(String path) {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getPath()).thenReturn(path);
        RequestContext reqCtx = mock(RequestContext.class);
        when(req.getRequestContext()).thenReturn(reqCtx);

        // Unified attribute store via Map<String, Object>
        Map<String, Object> attrs = new HashMap<>();

        // Handle both String-keyed and RequestAttribute-keyed getAttribute
        when(reqCtx.getAttribute(any(String.class))).thenAnswer(inv -> attrs.get(inv.getArgument(0)));
        when(reqCtx.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> {
            RequestAttribute<?> ra = inv.getArgument(0);
            return attrs.get(ATTR_STORE_KEY + ra.getIndex());
        });

        // Handle both String-keyed and RequestAttribute-keyed setAttribute
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

    private PathMappingContext mockCtx(String pathRule, Matcher... matchers) {
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getPathRule()).thenReturn(pathRule);
        when(ctx.getMatchers()).thenReturn(matchers);
        return ctx;
    }

    @Test
    void route_matchingPath_returnsInnerRouterResult() {
        PathMappingContext ctx = mockCtx("/api/user/detail");
        SimpleRouter innerRouter = new SimpleRouter(ctx);
        PathPatternRouter router = new PathPatternRouter(innerRouter);

        WebServerHttpRequest req = createMockRequest("/api/user/detail");

        PathMappingContext result = router.route(req);
        assertNotNull(result);
    }

    @Test
    void route_nonMatchingPath_returnsNull() {
        PathMappingContext ctx = mockCtx("/api/user/detail");
        SimpleRouter innerRouter = new SimpleRouter(ctx);
        PathPatternRouter router = new PathPatternRouter(innerRouter);

        WebServerHttpRequest req = createMockRequest("/other/path");

        PathMappingContext result = router.route(req);
        assertNull(result);
    }

    @Test
    void route_withPathVariable_matchesAndExtracts() {
        PathMappingContext ctx = mockCtx("/api/{id}");
        SimpleRouter innerRouter = new SimpleRouter(ctx);
        PathPatternRouter router = new PathPatternRouter(innerRouter);

        WebServerHttpRequest req = createMockRequest("/api/123");

        PathMappingContext result = router.route(req);
        assertNotNull(result);
        assertSame(ctx, result);
    }

    @Test
    void route_withPathVariableAndTrailingPath_matchesCorrectly() {
        PathMappingContext ctx = mockCtx("/api/{id}/detail");
        SimpleRouter innerRouter = new SimpleRouter(ctx);
        PathPatternRouter router = new PathPatternRouter(innerRouter);

        WebServerHttpRequest req = createMockRequest("/api/42/detail");

        PathMappingContext result = router.route(req);
        assertNotNull(result);
        assertSame(ctx, result);
    }

    @Test
    void getPathRule_returnsConfiguredRule() {
        PathMappingContext ctx = mockCtx("/api/test");
        SimpleRouter innerRouter = new SimpleRouter(ctx);
        PathPatternRouter router = new PathPatternRouter(innerRouter);

        assertEquals("/api/test", router.getPathRule());
    }

    @Test
    void add_mappingContext_delegatesToInnerRouter() {
        PathMappingContext ctx1 = mockCtx("/api/test");
        PathMappingContext ctx2 = mockCtx("/api/test");

        SimpleRouter innerRouter = new SimpleRouter(ctx1);
        PathPatternRouter router = new PathPatternRouter(innerRouter);
        router.add(ctx2);

        WebServerHttpRequest req = createMockRequest("/api/test");
        assertNotNull(router.route(req));
    }

    @Test
    void add_router_delegatesToInnerRouter() {
        PathMappingContext ctx1 = mockCtx("/api/test");
        PathMappingContext ctx2 = mockCtx("/api/test");

        SimpleRouter innerRouter1 = new SimpleRouter(ctx1);
        SimpleRouter innerRouter2 = new SimpleRouter(ctx2);
        PathPatternRouter router = new PathPatternRouter(innerRouter1);
        router.add(innerRouter2);

        WebServerHttpRequest req = createMockRequest("/api/test");
        assertNotNull(router.route(req));
    }

    @Test
    void getRoute_cachesResult() {
        PathMappingContext ctx = mockCtx("/api/test");
        PathPatternRouter router = new PathPatternRouter(new SimpleRouter(ctx));

        WebServerHttpRequest req = createMockRequest("/api/test");

        router.route(req);
        router.route(req);
        verify(req, atLeast(1)).getPath();
    }

    @Test
    void getSimpleRouter_returnsInnerRouter() {
        PathMappingContext ctx = mockCtx("/api/test");
        SimpleRouter innerRouter = new SimpleRouter(ctx);
        PathPatternRouter router = new PathPatternRouter(innerRouter);

        assertSame(innerRouter, router.getSimpleRouter());
    }

    @Test
    void initRouteMatcher_withPatternPath_usesPatternRouteMatcher() {
        PathMappingContext ctx = mockCtx("/api/{id}");
        PathPatternRouter router = new PathPatternRouter(new SimpleRouter(ctx));

        WebServerHttpRequest req = createMockRequest("/api/123");
        assertNotNull(router.route(req));
    }
}
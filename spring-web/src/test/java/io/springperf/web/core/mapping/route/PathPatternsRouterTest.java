package io.springperf.web.core.mapping.route;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.core.mapping.match.Matcher;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PathPatternsRouterTest {

    private static final String ATTR_STORE_KEY = "_attr_";

    private WebServerHttpRequest createMockRequest(String path) {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getPath()).thenReturn(path);
        RequestContext reqCtx = mock(RequestContext.class);
        when(req.getRequestContext()).thenReturn(reqCtx);
        Map<String, Object> attrs = new HashMap<>();

        when(reqCtx.getAttribute(any(String.class))).thenAnswer(inv -> attrs.get(inv.getArgument(0)));
        when(reqCtx.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> {
            RequestAttribute<?> ra = inv.getArgument(0);
            return attrs.get(ATTR_STORE_KEY + ra.getIndex());
        });
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
    void route_singleRouter_matchingPath_returnsContext() {
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getPathRule()).thenReturn("/api/test");
        when(ctx.getMatchers()).thenReturn(new Matcher[0]);

        PathPatternsRouter router = new PathPatternsRouter(ctx);
        WebServerHttpRequest req = createMockRequest("/api/test");

        assertNotNull(router.route(req));
    }

    @Test
    void route_singleRouter_nonMatchingPath_returnsNull() {
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getPathRule()).thenReturn("/api/test");
        when(ctx.getMatchers()).thenReturn(new Matcher[0]);

        PathPatternsRouter router = new PathPatternsRouter(ctx);
        WebServerHttpRequest req = createMockRequest("/other/path");

        assertNull(router.route(req));
    }

    @Test
    void route_multipleRouters_secondMatches() {
        PathMappingContext ctx1 = mock(PathMappingContext.class);
        when(ctx1.getPathRule()).thenReturn("/api/user");
        PathMappingContext ctx2 = mock(PathMappingContext.class);
        when(ctx2.getPathRule()).thenReturn("/api/test");
        when(ctx2.getMatchers()).thenReturn(new Matcher[0]);

        PathPatternsRouter router = new PathPatternsRouter();
        router.add(ctx1);
        router.add(ctx2);

        WebServerHttpRequest req = createMockRequest("/api/test");
        assertNotNull(router.route(req));
    }

    @Test
    void route_firstRouterMatches_returnsFirst() {
        PathMappingContext ctx1 = mock(PathMappingContext.class);
        when(ctx1.getPathRule()).thenReturn("/api/test");
        when(ctx1.getMatchers()).thenReturn(new Matcher[0]);
        PathMappingContext ctx2 = mock(PathMappingContext.class);
        when(ctx2.getPathRule()).thenReturn("/api/user");
        when(ctx2.getMatchers()).thenReturn(new Matcher[0]);

        PathPatternsRouter router = new PathPatternsRouter();
        router.add(ctx1);
        router.add(ctx2);

        WebServerHttpRequest req = createMockRequest("/api/test");
        assertSame(ctx1, router.route(req));
    }

    @Test
    void route_emptyRouter_returnsNull() {
        PathPatternsRouter router = new PathPatternsRouter();
        WebServerHttpRequest req = createMockRequest("/api/test");

        assertNull(router.route(req));
    }

    @Test
    void route_noneMatch_returnsNull() {
        PathMappingContext ctx1 = mock(PathMappingContext.class);
        when(ctx1.getPathRule()).thenReturn("/api/user");
        PathMappingContext ctx2 = mock(PathMappingContext.class);
        when(ctx2.getPathRule()).thenReturn("/api/order");

        PathPatternsRouter router = new PathPatternsRouter();
        router.add(ctx1);
        router.add(ctx2);

        WebServerHttpRequest req = createMockRequest("/api/test");
        assertNull(router.route(req));
    }

    @Test
    void add_router_samePath_deduplicates() {
        PathMappingContext ctx1 = mock(PathMappingContext.class);
        when(ctx1.getPathRule()).thenReturn("/api/test");
        when(ctx1.getMatchers()).thenReturn(new Matcher[0]);
        PathMappingContext ctx2 = mock(PathMappingContext.class);
        when(ctx2.getPathRule()).thenReturn("/api/test");
        when(ctx2.getMatchers()).thenReturn(new Matcher[0]);

        PathPatternsRouter router = new PathPatternsRouter();
        router.add(new SimpleRouter(ctx1));
        router.add(new SimpleRouter(ctx2));

        WebServerHttpRequest req = createMockRequest("/api/test");
        assertNotNull(router.route(req));
    }

    @Test
    void add_router_differentPaths_addsNewRouter() {
        PathMappingContext ctx1 = mock(PathMappingContext.class);
        when(ctx1.getPathRule()).thenReturn("/api/user");
        PathMappingContext ctx2 = mock(PathMappingContext.class);
        when(ctx2.getPathRule()).thenReturn("/api/test");
        when(ctx2.getMatchers()).thenReturn(new Matcher[0]);

        PathPatternsRouter router = new PathPatternsRouter();
        router.add(new SimpleRouter(ctx1));
        router.add(new SimpleRouter(ctx2));

        WebServerHttpRequest req = createMockRequest("/api/test");
        assertNotNull(router.route(req));
    }

    @Test
    void getPathRule_throwsUnsupportedOperationException() {
        PathPatternsRouter router = new PathPatternsRouter();
        assertThrows(UnsupportedOperationException.class, router::getPathRule);
    }

    @Test
    void add_PathPatternRouter_extractsSimpleRouter() {
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getPathRule()).thenReturn("/api/test");
        when(ctx.getMatchers()).thenReturn(new Matcher[0]);

        PathPatternsRouter router = new PathPatternsRouter();
        PathPatternRouter ppRouter = new PathPatternRouter(new SimpleRouter(ctx));
        router.add(ppRouter);

        WebServerHttpRequest req = createMockRequest("/api/test");
        assertNotNull(router.route(req));
    }

    @Test
    void multipleRoutersWithSamePath_matchesCorrectly() {
        PathMappingContext ctx1 = mock(PathMappingContext.class);
        when(ctx1.getPathRule()).thenReturn("/api/{id}");
        when(ctx1.getMatchers()).thenReturn(new Matcher[0]);
        PathMappingContext ctx2 = mock(PathMappingContext.class);
        when(ctx2.getPathRule()).thenReturn("/api/{version}");
        when(ctx2.getMatchers()).thenReturn(new Matcher[0]);

        PathPatternsRouter router = new PathPatternsRouter();
        router.add(new SimpleRouter(ctx1));
        router.add(new SimpleRouter(ctx2));

        WebServerHttpRequest req = createMockRequest("/api/123");
        assertNotNull(router.route(req));
    }
}
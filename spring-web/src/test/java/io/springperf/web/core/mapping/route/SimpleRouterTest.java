package io.springperf.web.core.mapping.route;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.match.Matcher;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SimpleRouterTest {

    private PathMappingContext createContext(String path, Matcher... matchers) {
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getPathRule()).thenReturn(path);
        when(ctx.getMatchers()).thenReturn(matchers);
        return ctx;
    }

    private WebServerHttpRequest createRequest() {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getRequestContext()).thenReturn(mock(RequestContext.class));
        return req;
    }

    @Test
    void route_noMatchers_returnsContext() {
        PathMappingContext ctx = createContext("/api/test");
        SimpleRouter router = new SimpleRouter(ctx);
        WebServerHttpRequest req = createRequest();
        assertSame(ctx, router.route(req));
    }

    @Test
    void route_allMatchersPass_returnsContext() {
        Matcher passMatcher = mock(Matcher.class);
        when(passMatcher.match(any(), any())).thenReturn(true);

        PathMappingContext ctx = createContext("/api/test", passMatcher);
        SimpleRouter router = new SimpleRouter(ctx);
        WebServerHttpRequest req = createRequest();
        assertSame(ctx, router.route(req));
    }

    @Test
    void route_anyMatcherFails_returnsNull() {
        Matcher passMatcher = mock(Matcher.class);
        when(passMatcher.match(any(), any())).thenReturn(true);
        Matcher failMatcher = mock(Matcher.class);
        when(failMatcher.match(any(), any())).thenReturn(false);

        PathMappingContext ctx = createContext("/api/test", passMatcher, failMatcher);
        SimpleRouter router = new SimpleRouter(ctx);
        WebServerHttpRequest req = createRequest();
        assertNull(router.route(req));
    }

    @Test
    void route_multipleContexts_secondMatches() {
        Matcher failMatcher = mock(Matcher.class);
        when(failMatcher.match(any(), any())).thenReturn(false);
        Matcher passMatcher = mock(Matcher.class);
        when(passMatcher.match(any(), any())).thenReturn(true);

        PathMappingContext ctx1 = createContext("/a", failMatcher);
        PathMappingContext ctx2 = createContext("/b", passMatcher);

        SimpleRouter router = new SimpleRouter(ctx1);
        router.add(ctx2);

        WebServerHttpRequest req = createRequest();
        assertSame(ctx2, router.route(req));
    }

    @Test
    void route_firstContextMatches_returnsFirst() {
        Matcher passMatcher = mock(Matcher.class);
        when(passMatcher.match(any(), any())).thenReturn(true);

        PathMappingContext ctx1 = createContext("/a", passMatcher);
        PathMappingContext ctx2 = createContext("/b", passMatcher);

        SimpleRouter router = new SimpleRouter(ctx1);
        router.add(ctx2);

        WebServerHttpRequest req = createRequest();
        assertSame(ctx1, router.route(req));
    }

    @Test
    void route_noneMatch_returnsNull() {
        Matcher failMatcher = mock(Matcher.class);
        when(failMatcher.match(any(), any())).thenReturn(false);

        PathMappingContext ctx1 = createContext("/a", failMatcher);
        PathMappingContext ctx2 = createContext("/b", failMatcher);

        SimpleRouter router = new SimpleRouter(ctx1);
        router.add(ctx2);

        WebServerHttpRequest req = createRequest();
        assertNull(router.route(req));
    }

    @Test
    void getPathRule_returnsFirstContextPath() {
        PathMappingContext ctx = createContext("/api/test");
        SimpleRouter router = new SimpleRouter(ctx);
        assertEquals("/api/test", router.getPathRule());
    }

    @Test
    void add_context_increasesCount() {
        PathMappingContext ctx1 = createContext("/a");
        PathMappingContext ctx2 = createContext("/b");

        SimpleRouter router = new SimpleRouter(ctx1);
        router.add(ctx2);

        // No matchers so both match, first one returned
        WebServerHttpRequest req = createRequest();
        assertSame(ctx1, router.route(req));
    }

    @Test
    void add_router_mergesContexts() {
        PathMappingContext ctx1 = createContext("/a");
        PathMappingContext ctx2 = createContext("/b");
        PathMappingContext ctx3 = createContext("/c");

        SimpleRouter r1 = new SimpleRouter(ctx1);
        r1.add(ctx2);

        SimpleRouter r2 = new SimpleRouter(ctx3);
        r1.add(r2);

        // All three should be present, first one returned
        WebServerHttpRequest req = createRequest();
        assertSame(ctx1, r1.route(req));
    }

    @Test
    void add_invalidRouterType_throws() {
        PathMappingContext ctx = createContext("/a");
        SimpleRouter router = new SimpleRouter(ctx);
        Router invalidRouter = mock(Router.class);
        assertThrows(IllegalArgumentException.class, () -> router.add(invalidRouter));
    }
}

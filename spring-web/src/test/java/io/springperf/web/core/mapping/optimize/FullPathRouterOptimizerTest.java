package io.springperf.web.core.mapping.optimize;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.route.Router;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FullPathRouterOptimizerTest {

    @Test
    void initAndRemove_addsToRouteMapAndReturnsTrue() {
        FullPathRouterOptimizer optimizer = new FullPathRouterOptimizer();
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getPathRule()).thenReturn("/api/test");

        assertTrue(optimizer.initAndRemove(ctx));
    }

    @Test
    void optimizeRoute_exactPathMatch_returnsRouter() {
        FullPathRouterOptimizer optimizer = new FullPathRouterOptimizer();
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getPathRule()).thenReturn("/api/test");
        optimizer.initAndRemove(ctx);

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getPath()).thenReturn("/api/test");

        Router router = optimizer.optimizeRoute(req);
        assertNotNull(router);
    }

    @Test
    void optimizeRoute_noMatch_returnsNull() {
        FullPathRouterOptimizer optimizer = new FullPathRouterOptimizer();
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getPathRule()).thenReturn("/api/test");
        optimizer.initAndRemove(ctx);

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getPath()).thenReturn("/api/other");

        Router router = optimizer.optimizeRoute(req);
        assertNull(router);
    }

    @Test
    void optimizeRoute_emptyRouteMap_returnsNull() {
        FullPathRouterOptimizer optimizer = new FullPathRouterOptimizer();
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getPath()).thenReturn("/api/test");

        assertNull(optimizer.optimizeRoute(req));
    }

    @Test
    void putSimpleUrl_newPath_createsNewRouter() {
        java.util.Map<String, Router> urlMap = new java.util.HashMap<>();
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getPathRule()).thenReturn("/api/test");

        FullPathRouterOptimizer.putSimpleUrl(urlMap, ctx);
        assertTrue(urlMap.containsKey("/api/test"));
    }

    @Test
    void putSimpleUrl_existingPath_addsToExistingRouter() {
        java.util.Map<String, Router> urlMap = new java.util.HashMap<>();
        PathMappingContext ctx1 = mock(PathMappingContext.class);
        when(ctx1.getPathRule()).thenReturn("/api/test");
        PathMappingContext ctx2 = mock(PathMappingContext.class);
        when(ctx2.getPathRule()).thenReturn("/api/test");

        FullPathRouterOptimizer.putSimpleUrl(urlMap, ctx1);
        FullPathRouterOptimizer.putSimpleUrl(urlMap, ctx2);

        assertTrue(urlMap.containsKey("/api/test"));
    }

    @Test
    void putWildcardUrl_newPath_createsNewPathPatternsRouter() {
        java.util.Map<String, Router> urlMap = new java.util.HashMap<>();
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getPathRule()).thenReturn("/api/{id}");

        FullPathRouterOptimizer.putWildcardUrl(urlMap, "/api", ctx);
        assertTrue(urlMap.containsKey("/api"));
    }

    @Test
    void putWildcardUrl_existingPath_addsToExistingRouter() {
        java.util.Map<String, Router> urlMap = new java.util.HashMap<>();
        PathMappingContext ctx1 = mock(PathMappingContext.class);
        when(ctx1.getPathRule()).thenReturn("/api/{id}");
        PathMappingContext ctx2 = mock(PathMappingContext.class);
        when(ctx2.getPathRule()).thenReturn("/api/{version}");

        FullPathRouterOptimizer.putWildcardUrl(urlMap, "/api", ctx1);
        FullPathRouterOptimizer.putWildcardUrl(urlMap, "/api", ctx2);

        assertTrue(urlMap.containsKey("/api"));
    }

    @Test
    void support_nonEmptyList_returnsTrue() {
        FullPathRouterOptimizer optimizer = new FullPathRouterOptimizer();
        assertTrue(optimizer.support(Collections.singletonList(mock(PathMappingContext.class))));
    }

    @Test
    void support_emptyList_returnsFalse() {
        FullPathRouterOptimizer optimizer = new FullPathRouterOptimizer();
        assertFalse(optimizer.support(Collections.emptyList()));
    }

    @Test
    void support_nullList_returnsFalse() {
        FullPathRouterOptimizer optimizer = new FullPathRouterOptimizer();
        assertFalse(optimizer.support(null));
    }

    @Test
    void multipleContextsSamePath_sharedRouter() {
        FullPathRouterOptimizer optimizer = new FullPathRouterOptimizer();
        PathMappingContext ctx1 = mock(PathMappingContext.class);
        when(ctx1.getPathRule()).thenReturn("/api/test");
        PathMappingContext ctx2 = mock(PathMappingContext.class);
        when(ctx2.getPathRule()).thenReturn("/api/test");

        optimizer.initAndRemove(ctx1);
        optimizer.initAndRemove(ctx2);

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getPath()).thenReturn("/api/test");

        assertNotNull(optimizer.optimizeRoute(req));
    }
}
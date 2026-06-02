package io.springperf.web.core.mapping.optimize;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.route.Router;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoopPathPatternRouterOptimizerTest {

    @Test
    void initAndRemove_addsContextAndReturnsTrue() {
        LoopPathPatternRouterOptimizer optimizer = new LoopPathPatternRouterOptimizer();
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getPathRule()).thenReturn("/api/{id}");

        assertTrue(optimizer.initAndRemove(ctx));
    }

    @Test
    void initAndRemove_addMultipleContexts() {
        LoopPathPatternRouterOptimizer optimizer = new LoopPathPatternRouterOptimizer();
        PathMappingContext ctx1 = mock(PathMappingContext.class);
        when(ctx1.getPathRule()).thenReturn("/api/{id}");
        PathMappingContext ctx2 = mock(PathMappingContext.class);
        when(ctx2.getPathRule()).thenReturn("/api/{version}");

        assertTrue(optimizer.initAndRemove(ctx1));
        assertTrue(optimizer.initAndRemove(ctx2));
    }

    @Test
    void optimizeRoute_returnsInnerRouter() {
        LoopPathPatternRouterOptimizer optimizer = new LoopPathPatternRouterOptimizer();
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getPathRule()).thenReturn("/api/{id}");
        optimizer.initAndRemove(ctx);

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        Router router = optimizer.optimizeRoute(req);
        assertNotNull(router);
    }

    @Test
    void optimizeRoute_noContext_returnsEmptyRouter() {
        LoopPathPatternRouterOptimizer optimizer = new LoopPathPatternRouterOptimizer();
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        Router router = optimizer.optimizeRoute(req);
        assertNotNull(router);
    }
}
package io.springperf.web.core.mapping.optimize;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SuffixPathRouterOptimizerTest {

    private PathMappingContext mockContext(String pathRule) {
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getPathRule()).thenReturn(pathRule);
        return ctx;
    }

    @Test
    void support_twoPathsWithCommonSuffix_returnsFalse() {
        SuffixPathRouterOptimizer optimizer = new SuffixPathRouterOptimizer();
        List<PathMappingContext> list = new ArrayList<>();
        list.add(mockContext("/api/v1/user/detail"));
        list.add(mockContext("/api/v2/user/detail"));
        assertFalse(optimizer.support(list));
    }

    @Test
    void support_singlePath_returnsFalse() {
        SuffixPathRouterOptimizer optimizer = new SuffixPathRouterOptimizer();
        List<PathMappingContext> list = new ArrayList<>();
        list.add(mockContext("/api/user/list"));
        assertFalse(optimizer.support(list));
    }

    @Test
    void support_emptyList_returnsFalse() {
        SuffixPathRouterOptimizer optimizer = new SuffixPathRouterOptimizer();
        assertFalse(optimizer.support(new ArrayList<>()));
    }

    @Test
    void initAndRemove_shortPath_returnsFalse() {
        SuffixPathRouterOptimizer optimizer = new SuffixPathRouterOptimizer();
        List<PathMappingContext> list = new ArrayList<>();
        list.add(mockContext("/api/v1/user/detail"));
        list.add(mockContext("/api/v2/user/detail"));
        list.add(mockContext("/api/v3/user/detail"));
        optimizer.support(list);
        PathMappingContext ctx = mockContext("/a");
        assertFalse(optimizer.initAndRemove(ctx));
    }

    @Test
    void optimizeRoute_noOptimizerData_throwsArrayIndexOutOfBounds() {
        SuffixPathRouterOptimizer optimizer = new SuffixPathRouterOptimizer();
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getPath()).thenReturn("/api/v1/user/detail");
        when(req.getRequestContext()).thenReturn(mock());
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> optimizer.optimizeRoute(req));
    }
}
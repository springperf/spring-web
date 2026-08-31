package io.springperf.web.core.mapping.optimize;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.route.Router;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrefixPathRouterOptimizerTest {

    private static final String ATTR_STORE_KEY = "_attr_";

    private PathMappingContext mockContext(String pathRule) {
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getPathRule()).thenReturn(pathRule);
        return ctx;
    }

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
        doAnswer(inv -> attrs.put(inv.getArgument(0), inv.getArgument(1))).when(reqCtx).setAttribute(any(String.class), any());
        doAnswer(inv -> {
            RequestAttribute<?> ra = inv.getArgument(0);
            attrs.put(ATTR_STORE_KEY + ra.getIndex(), inv.getArgument(1));
            return null;
        }).when(reqCtx).setAttribute(any(RequestAttribute.class), any());
        return req;
    }

    @Test
    void support_twoPaths_returnsFalse() {
        PrefixPathRouterOptimizer optimizer = new PrefixPathRouterOptimizer();
        List<PathMappingContext> list = new ArrayList<>();
        list.add(mockContext("/api/user/list"));
        list.add(mockContext("/api/user/detail"));
        assertFalse(optimizer.support(list));
    }

    @Test
    void support_singlePath_returnsFalse() {
        PrefixPathRouterOptimizer optimizer = new PrefixPathRouterOptimizer();
        List<PathMappingContext> list = new ArrayList<>();
        list.add(mockContext("/api/user/list"));
        assertFalse(optimizer.support(list));
    }

    @Test
    void support_emptyList_returnsFalse() {
        PrefixPathRouterOptimizer optimizer = new PrefixPathRouterOptimizer();
        assertFalse(optimizer.support(new ArrayList<>()));
    }

    @Test
    void getSlashIndexList_cachesResult() {
        WebServerHttpRequest req = createMockRequest("/a/b/c");
        int[] first = PrefixPathRouterOptimizer.getSlashIndexList(req);
        int[] second = PrefixPathRouterOptimizer.getSlashIndexList(req);
        assertArrayEquals(first, second);
    }

    @Test
    void getSlashIndexList_returnsCorrectIndices() {
        WebServerHttpRequest req = createMockRequest("/api/user/list");
        int[] indices = PrefixPathRouterOptimizer.getSlashIndexList(req);
        assertArrayEquals(new int[]{0, 4, 9}, indices);
    }

    @Test
    void getSlashIndexList_rootPath_returnsSingleZero() {
        WebServerHttpRequest req = createMockRequest("/");
        int[] indices = PrefixPathRouterOptimizer.getSlashIndexList(req);
        assertArrayEquals(new int[]{0}, indices);
    }

    @Test
    void optimizeRoute_noSupportCalled_returnsNull() {
        // 未调用 support()/init() 时 prefixPathIndex 未初始化（<0），
        // 与 SuffixPathRouterOptimizer 对称：优雅返回 null，而非访问 slashIndexList 越界
        PrefixPathRouterOptimizer optimizer = new PrefixPathRouterOptimizer();
        WebServerHttpRequest req = createMockRequest("/api/test");
        assertNull(optimizer.optimizeRoute(req));
    }

    @Test
    void optimizeRoute_afterSupportButNoInit_returnsNull() {
        PrefixPathRouterOptimizer optimizer = new PrefixPathRouterOptimizer();
        List<PathMappingContext> list = new ArrayList<>();
        list.add(mockContext("/a/b/c/d/e"));
        list.add(mockContext("/a/b/c/d/f"));
        list.add(mockContext("/a/b/c/d/g"));
        optimizer.support(list);
        WebServerHttpRequest req = createMockRequest("/other/path");
        Router router = optimizer.optimizeRoute(req);
        assertNull(router);
    }

    @Test
    void initAndRemove_prefixPathHasWildcard_returnsFalse() {
        // 构造三条共享 /a/b/ 前缀但通配段不同的路径，support() 会选中 prefixPathIndex=3；
        // 再用前缀段含 {c} 的路径触发 initAndRemove 的 pathHaveWildcard(prefixPath) 分支
        PrefixPathRouterOptimizer optimizer = new PrefixPathRouterOptimizer();
        List<PathMappingContext> list = new ArrayList<>();
        list.add(mockContext("/a/b/x/**"));
        list.add(mockContext("/a/b/y/**"));
        list.add(mockContext("/a/b/z/**"));
        optimizer.support(list);
        assertFalse(optimizer.initAndRemove(mockContext("/a/b/{c}/d")), "前缀段含通配符时不应建前缀路由");
    }

    @Test
    void initAndRemove_shortPath_returnsFalse() {
        PrefixPathRouterOptimizer optimizer = new PrefixPathRouterOptimizer();
        List<PathMappingContext> list = new ArrayList<>();
        list.add(mockContext("/a/b/c/d/e"));
        list.add(mockContext("/a/b/c/d/f"));
        list.add(mockContext("/a/b/c/d/g"));
        optimizer.support(list);
        PathMappingContext ctx = mockContext("/a");
        assertFalse(optimizer.initAndRemove(ctx));
    }
}
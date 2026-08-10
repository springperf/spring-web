package io.springperf.web.core.mapping.optimize;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void optimizeRoute_noOptimizerData_returnsNull() {
        SuffixPathRouterOptimizer optimizer = new SuffixPathRouterOptimizer();
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getPath()).thenReturn("/api/v1/user/detail");
        when(req.getRequestContext()).thenReturn(mock(RequestContext.class));
        assertNull(optimizer.optimizeRoute(req));
    }

    /**
     * 回归 R2-2：suffixPathIndex 从末尾锚定，initAndRemove / optimizeRoute 的 slash 下标
     * 必须取「倒数第 suffixPathIndex 个」。修复前：
     * <ul>
     *   <li>initAndRemove 用 {@code slashIndexList[suffixPathIndex - 1]}（从头数），substring
     *       必含 {@code {id}} 通配段 → 全部 return false，routeMap 永不填充；</li>
     *   <li>optimizeRoute 用 {@code slashIndexList[suffixPathIndex]}，查询 key 偏移，永远 miss。</li>
     * </ul>
     * 结果后缀路由优化器形同虚设。修复后两条路径均取 {@code slashIndexList[length - suffixPathIndex]}。
     */
    @Test
    void suffixOptimizer_activatesAndRoutes() {
        SuffixPathRouterOptimizer optimizer = new SuffixPathRouterOptimizer();
        List<PathMappingContext> list = new ArrayList<>();
        // 3 条共享 "/user/detail" + 3 条共享 "/order/detail"，score 触发后缀优化（suffixPathIndex=2）
        for (int i = 1; i <= 3; i++) {
            list.add(mockContext("/api/v" + i + "/{id}/user/detail"));
            list.add(mockContext("/api/v" + i + "/{id}/order/detail"));
        }
        assertTrue(optimizer.support(list));

        // 每条规则都能摘出无通配符后缀并入库
        for (int i = 1; i <= 3; i++) {
            assertTrue(optimizer.initAndRemove(mockContext("/api/v" + i + "/{id}/user/detail")));
            assertTrue(optimizer.initAndRemove(mockContext("/api/v" + i + "/{id}/order/detail")));
        }

        // 请求 "/api/v1/123/user/detail" 以 "/user/detail" 为 key 命中对应 router
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getPath()).thenReturn("/api/v1/123/user/detail");
        when(req.getRequestContext()).thenReturn(mock(RequestContext.class));
        assertNotNull(optimizer.optimizeRoute(req));
    }
}
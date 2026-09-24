package io.springperf.web.core.mapping.optimize;

import java.util.HashMap;
import java.util.Map;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.route.PathPatternsRouter;
import io.springperf.web.core.mapping.route.Router;
import io.springperf.web.core.mapping.route.SimpleRouter;
import io.springperf.web.http.WebServerHttpRequest;

public class FullPathRouterOptimizer implements RouterOptimizer {

    private final Map<String, Router> routeMap = new HashMap<>();

    /**
     * 供 MappingRegistry 在初始化后注册新路由时获取 routeMap。
     */
    public Map<String, Router> getRouteMap() {
        // 只读视图；字段为 null 时保持原有返回 null 的行为
        return routeMap == null ? null : java.util.Collections.unmodifiableMap(routeMap);
    }

    @Override
    public boolean initAndRemove(PathMappingContext mappingContext) {
        putSimpleUrl(routeMap, mappingContext);
        return true;
    }

    public static void putSimpleUrl(Map<String, Router> urlMap, PathMappingContext methodMappingContext) {
        String path = methodMappingContext.getPathRule();
        if (urlMap.containsKey(path)) {
            Router router = urlMap.get(path);
            router.add(methodMappingContext);
        } else {
            urlMap.put(path, new SimpleRouter(methodMappingContext));
        }
    }

    public static void putWildcardUrl(Map<String, Router> urlMap, String path,
            PathMappingContext methodMappingContext) {
        if (urlMap.containsKey(path)) {
            Router router = urlMap.get(path);
            router.add(methodMappingContext);
        } else {
            urlMap.put(path, new PathPatternsRouter(methodMappingContext));
        }
    }

    @Override
    public Router optimizeRoute(WebServerHttpRequest req) {
        return routeMap.get(req.getPath());
    }

}

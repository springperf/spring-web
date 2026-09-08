package io.springperf.web.core.mapping.route;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.util.PathPatternUtils;

import java.util.Arrays;

public class PathPatternsRouter implements Router {

    private PathPatternRouter[] routers;

    /** 按路径特异性排序（literal > {var} > * > **），重叠通配符时最精确者优先。 */
    private static final java.util.Comparator<PathPatternRouter> SPECIFICITY_COMPARATOR =
            (a, b) -> PathPatternUtils.comparePathRuleSpecificity(a.getPathRule(), b.getPathRule());

    public PathPatternsRouter() {
        routers = new PathPatternRouter[0];
    }

    public PathPatternsRouter(PathMappingContext mappingContext) {
        this();
        add(mappingContext);
    }

    @Override
    public PathMappingContext route(WebServerHttpRequest req) {
        for (PathPatternRouter router : routers) {
            PathMappingContext mappingContext = router.route(req);
            if (mappingContext != null) {
                return mappingContext;
            }
        }
        return null;
    }

    protected void addPathPatternRouter(PathPatternRouter pathPatternRouter) {
        routers = Arrays.copyOf(routers, routers.length + 1);
        routers[routers.length - 1] = pathPatternRouter;
    }

    @Override
    public String getPathRule() {
        throw new UnsupportedOperationException();
    }

    public void add(PathMappingContext mappingContext) {
        add(new SimpleRouter(mappingContext));
    }

    public void add(Router router) {
        if (routers.length == 0) {
            addPathPatternRouter(new PathPatternRouter(router));
            return;
        }
        if (router instanceof PathPatternRouter) {
            router = ((PathPatternRouter) router).getSimpleRouter();
        }
        String pathRule = router.getPathRule();
        for (PathPatternRouter pathPatternRouter : routers) {
            if (pathPatternRouter.getPathRule().equals(pathRule)) {
                pathPatternRouter.add(router);
                return;
            }
        }
        addPathPatternRouter(new PathPatternRouter(router));
        // 启动/修改时（而非请求时）按路径特异性排序：重叠通配符（如 /user/{id} vs /user/*）
        // 最精确者优先匹配；非重叠路由排序不影响命中；同特异性由稳定排序保持注册顺序。
        // copy+swap 原子换引用，读者只看到完整数组，无中间态。
        PathPatternRouter[] sorted = Arrays.copyOf(routers, routers.length);
        Arrays.sort(sorted, SPECIFICITY_COMPARATOR);
        routers = sorted;
    }
}

package io.springperf.web.core.mapping.route;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;

import java.util.Arrays;

public class PathPatternsRouter implements Router {

    private PathPatternRouter[] routers;

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
    }
}

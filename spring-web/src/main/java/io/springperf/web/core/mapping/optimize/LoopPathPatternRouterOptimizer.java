package io.springperf.web.core.mapping.optimize;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.route.PathPatternsRouter;
import io.springperf.web.core.mapping.route.Router;
import io.springperf.web.http.WebServerHttpRequest;

public class LoopPathPatternRouterOptimizer implements RouterOptimizer {

    PathPatternsRouter router = new PathPatternsRouter();

    @Override
    public boolean initAndRemove(PathMappingContext mappingContext) {
        router.add(mappingContext);
        return true;
    }

    @Override
    public Router optimizeRoute(WebServerHttpRequest req) {
        return router;
    }
}

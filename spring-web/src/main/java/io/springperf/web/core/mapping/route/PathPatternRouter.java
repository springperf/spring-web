package io.springperf.web.core.mapping.route;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.util.PathPatternUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.RouteMatcher;
import org.springframework.util.SimpleRouteMatcher;

import java.util.Map;

@Slf4j
public class PathPatternRouter implements Router {

    public static final RequestAttribute<Map<String, String>> URI_VARIABLE_MAP_ATTRIBUTE = (RequestAttribute) RequestAttribute.createAttribute(Map.class);

    protected static final RequestAttribute<RouteMatcher.Route> ROUTE_REQUEST_ATTRIBUTE = RequestAttribute.createAttribute(RouteMatcher.Route.class);

    public static Map<String, String> getUriVariableMap(WebServerHttpRequest req) {
        return req.getRequestContext().getAttribute(URI_VARIABLE_MAP_ATTRIBUTE);
    }

    private String pathRule;

    private Router simpleRouter;

    private RouteMatcher routeMatcher;

    public PathPatternRouter(Router router) {
        this.simpleRouter = router;
        this.pathRule = router.getPathRule();
        initRouteMatcher();
    }

    @Override
    public PathMappingContext route(WebServerHttpRequest req) {
        RouteMatcher.Route route = getRoute(req);
        Map<String, String> uriVariableMap = routeMatcher.matchAndExtract(pathRule, route);
        if (uriVariableMap != null) {
            req.getRequestContext().setAttribute(URI_VARIABLE_MAP_ATTRIBUTE, uriVariableMap);
            return simpleRouter.route(req);
        }
        return null;
    }

    protected RouteMatcher.Route getRoute(WebServerHttpRequest req) {
        RouteMatcher.Route route = req.getRequestContext().getAttribute(ROUTE_REQUEST_ATTRIBUTE);
        if (route == null) {
            if (routeMatcher instanceof SimpleRouteMatcher) {
                route = PathPatternUtils.getPatternRouteMatcher().parseRoute(req.getPath());
            } else {
                route = routeMatcher.parseRoute(req.getPath());
            }
            req.getRequestContext().setAttribute(ROUTE_REQUEST_ATTRIBUTE, route);
        }
        return route;
    }

    @Override
    public String getPathRule() {
        return pathRule;
    }

    @Override
    public void add(PathMappingContext methodMappingContext) {
        simpleRouter.add(methodMappingContext);
    }

    @Override
    public void add(Router router) {
        simpleRouter.add(router);
    }

    protected void initRouteMatcher() {
        String pathRule = simpleRouter.getPathRule();
        if (PathPatternUtils.supportPatternParse(pathRule)) {
            routeMatcher = PathPatternUtils.getPatternRouteMatcher();
        } else {
            log.warn("PathPatternRouter not support pathRule : {}", pathRule);
            routeMatcher = PathPatternUtils.getPathRouteMatcher();
        }
    }

    protected Router getSimpleRouter() {
        return simpleRouter;
    }
}

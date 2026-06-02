package io.springperf.web.core.mapping.route;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.match.Matcher;
import io.springperf.web.http.WebServerHttpRequest;

import java.util.Arrays;

public class SimpleRouter implements Router {

    private PathMappingContext[] methodMappingContexts;

    public SimpleRouter(PathMappingContext methodMappingContext) {
        this.methodMappingContexts = new PathMappingContext[]{methodMappingContext};
    }

    @Override
    public PathMappingContext route(WebServerHttpRequest req) {
        PathMappingContext.setMatchPathMappingContexts(req, methodMappingContexts);
        for (PathMappingContext methodMappingContext : methodMappingContexts) {
            Matcher[] matchers = methodMappingContext.getMatchers();
            if (matchers.length == 0) {
                return methodMappingContext;
            }
            boolean allMatch = true;
            for (Matcher matcher : matchers) {
                if (!matcher.match(req, methodMappingContext)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                return methodMappingContext;
            }
        }
        return null;
    }

    @Override
    public String getPathRule() {
        return methodMappingContexts[0].getPathRule();
    }

    public void add(PathMappingContext methodMappingContext) {
        methodMappingContexts = Arrays.copyOf(methodMappingContexts, methodMappingContexts.length + 1);
        methodMappingContexts[methodMappingContexts.length - 1] = methodMappingContext;
    }

    @Override
    public void add(Router router) {
        if (router instanceof SimpleRouter == false) {
            throw new IllegalArgumentException();
        }
        SimpleRouter simpleRouter = (SimpleRouter) router;
        int oldLength = methodMappingContexts.length;
        int newLength = methodMappingContexts.length + simpleRouter.methodMappingContexts.length;
        methodMappingContexts = Arrays.copyOf(methodMappingContexts, newLength);
        for (int i = 0; i < simpleRouter.methodMappingContexts.length; i++) {
            methodMappingContexts[oldLength + i] = simpleRouter.methodMappingContexts[i];
        }
    }
}

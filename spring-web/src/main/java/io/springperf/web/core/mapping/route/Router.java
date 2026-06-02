package io.springperf.web.core.mapping.route;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;

public interface Router {

    PathMappingContext route(WebServerHttpRequest req);

    String getPathRule();

    void add(PathMappingContext methodMappingContext);

    void add(Router router);
}

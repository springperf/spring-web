package io.springperf.web.core.mapping.route;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;

/**
 * Strategy for matching an incoming request to a single {@link PathMappingContext}.
 *
 * <p>Each {@code Router} encapsulates a routing strategy for a specific path
 * pattern. Given a request, it either returns a matching
 * {@link PathMappingContext} or {@code null} if no match is found.</p>
 *
 * <p>Routers are organized hierarchically: a router may contain sub-routers
 * (added via {@link #add(Router)}), forming a tree structure that enables
 * efficient path-based dispatch.</p>
 *
 * @since 1.0.0
 * @see PathMappingContext
 */
public interface Router {

    /**
     * Match the request to a {@link PathMappingContext}.
     *
     * @param req the incoming HTTP request
     * @return the matched mapping context, or {@code null} if no match
     */
    PathMappingContext route(WebServerHttpRequest req);

    /**
     * Return the path pattern this router handles.
     *
     * @return the path pattern string
     */
    String getPathRule();

    /**
     * Add a {@link PathMappingContext} under this router.
     *
     * @param methodMappingContext the mapping context to add
     */
    void add(PathMappingContext methodMappingContext);

    /**
     * Add a sub-router under this router.
     *
     * @param router the sub-router to add
     */
    void add(Router router);
}

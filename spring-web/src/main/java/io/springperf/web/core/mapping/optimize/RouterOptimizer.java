package io.springperf.web.core.mapping.optimize;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.route.Router;
import io.springperf.web.http.WebServerHttpRequest;

import java.util.Iterator;
import java.util.List;

/**
 * Strategy for selecting and initializing an optimal {@link Router} for a
 * set of path mappings.
 *
 * <p>During Phase 3 initialization, {@code RouterOptimizer} implementations
 * inspect the registered mappings and decide whether they can produce a
 * more efficient router (e.g., a hash-map-based lookup for simple paths vs.
 * a tree walk for wildcard patterns). Mappings claimed by an optimizer
 * (via {@link #initAndRemove}) are removed from the general pool.</p>
 *
 * @since 1.0.0
 * @see io.springperf.web.core.mapping.MappingRegistry
 */
public interface RouterOptimizer {

    /**
     * Check whether this optimizer can handle the given list of mappings.
     *
     * @param list the mappings to evaluate
     * @return {@code true} if this optimizer can optimize the list
     */
    default boolean support(List<PathMappingContext> list) {
        return list != null && !list.isEmpty();
    }

    /**
     * Initialize the optimizer with a list of mappings.
     *
     * <p>The default implementation iterates the list and calls
     * {@link #initAndRemove} for each mapping, removing claimed ones.</p>
     *
     * @param list the mappings to initialize from (may be modified by removal)
     */
    default void init(List<PathMappingContext> list) {
        Iterator<PathMappingContext> iterator = list.listIterator();
        while (iterator.hasNext()) {
            PathMappingContext mappingContext = iterator.next();
            if (initAndRemove(mappingContext)) {
                iterator.remove();
            }
        }
    }

    /**
     * Attempt to take ownership of a single mapping.
     *
     * @param mappingContext the mapping to evaluate
     * @return {@code true} if this optimizer has claimed the mapping
     */
    default boolean initAndRemove(PathMappingContext mappingContext) {
        return false;
    }

    /**
     * Return the optimized router for the given request.
     *
     * @param req the incoming HTTP request
     * @return the router, or {@code null} if no optimizer applies
     */
    Router optimizeRoute(WebServerHttpRequest req);
}

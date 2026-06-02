package io.springperf.web.core.mapping.optimize;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.route.Router;
import io.springperf.web.http.WebServerHttpRequest;

import java.util.Iterator;
import java.util.List;

public interface RouterOptimizer {

    default boolean support(List<PathMappingContext> list) {
        return list != null && !list.isEmpty();
    }

    default void init(List<PathMappingContext> list) {
        Iterator<PathMappingContext> iterator = list.listIterator();
        while (iterator.hasNext()) {
            PathMappingContext mappingContext = iterator.next();
            if (initAndRemove(mappingContext)) {
                iterator.remove();
            }
        }
    }

    default boolean initAndRemove(PathMappingContext mappingContext) {
        return false;
    }

    Router optimizeRoute(WebServerHttpRequest req);
}

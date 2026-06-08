package io.springperf.web.core.arg.resolver;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.util.MultiValueMap;

/**
 * Strategy for resolving a {@link org.springframework.util.MultiValueMap}
 * from request parameters.
 *
 * <p>Used by {@code @RequestParam} and similar argument resolvers to collect
 * multiple values under the same parameter name into a single map structure.
 * The generic type {@code T} represents the value type of the map entries.</p>
 *
 * @since 1.0.0
 * @param <T> the value type of the multi-value map
 */
public interface MultiValueMapResolver<T> {

    /**
     * Resolve a multi-value map from the current request.
     *
     * @param parameter      the method parameter descriptor
     * @param mappingContext the handler method metadata
     * @param request        the current HTTP request
     * @param response       the current HTTP response
     * @return the resolved multi-value map
     * @throws Exception if resolution fails
     */
    MultiValueMap<String, T> resolveMultiValueMap(MethodParameter parameter, MappingHandlerMethod mappingContext, WebServerHttpRequest request, WebServerHttpResponse response) throws Exception;
}

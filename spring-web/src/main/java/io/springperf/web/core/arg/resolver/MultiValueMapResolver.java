package io.springperf.web.core.arg.resolver;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.util.MultiValueMap;

public interface MultiValueMapResolver<T> {

    MultiValueMap<String, T> resolveMultiValueMap(MethodParameter parameter, MappingHandlerMethod mappingContext, WebServerHttpRequest request, WebServerHttpResponse response) throws Exception;
}

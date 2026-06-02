package io.springperf.web.core.retval;

import io.springperf.web.context.WebComponent;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;

public interface ReturnValueResolver extends WebComponent {

    boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext);

    boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp);

    void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception;
}

package io.springperf.web.core.retval.resolver;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.retval.ReturnValueResolver;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;

public class ByteArrayReturnValueResolver implements ReturnValueResolver {
    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        return byte[].class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        return returnValue instanceof byte[];
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        byte[] bytes = (byte[]) returnValue;
        resp.writeBytes(bytes);
    }
}

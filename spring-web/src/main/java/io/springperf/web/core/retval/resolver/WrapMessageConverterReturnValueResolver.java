package io.springperf.web.core.retval.resolver;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.retval.ReturnValueResolver;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.HttpMessageConverter;

public class WrapMessageConverterReturnValueResolver implements ReturnValueResolver {

    private final HttpMessageConverter messageConverter;

    public WrapMessageConverterReturnValueResolver(HttpMessageConverter messageConverter) {
        this.messageConverter = messageConverter;
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        return messageConverter.canWrite(returnType.getParameterType(), null);
    }

    @Override
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        return messageConverter.canWrite(returnValue.getClass(), null);
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        messageConverter.write(returnValue, null, resp);
    }
}

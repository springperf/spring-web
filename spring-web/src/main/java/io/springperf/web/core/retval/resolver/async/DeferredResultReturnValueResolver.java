package io.springperf.web.core.retval.resolver.async;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.async.DeferredResult;

public class DeferredResultReturnValueResolver extends BaseAsyncReturnValueResolver {
    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        return DeferredResult.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        return returnValue instanceof DeferredResult;
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        DeferredResult<?> result = (DeferredResult<?>) returnValue;
        asyncSupportRegistry.startDeferredResultProcessing(req, resp, result);
    }
}

package io.springperf.web.core.retval.resolver.async;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.async.DeferredResult;

public class ListenableFutureReturnValueResolver extends BaseAsyncReturnValueResolver {
    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        if (returnType == null) {
            return false;
        }
        return ListenableFutureAdapter.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        return ListenableFutureAdapter.isInstance(returnValue);
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        DeferredResult result = ListenableFutureAdapter.adapt(returnValue);
        asyncSupportRegistry.startDeferredResultProcessing(req, resp, result);
    }
}

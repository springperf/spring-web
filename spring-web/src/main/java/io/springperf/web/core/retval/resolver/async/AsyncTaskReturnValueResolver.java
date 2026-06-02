package io.springperf.web.core.retval.resolver.async;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.async.WebAsyncTask;

public class AsyncTaskReturnValueResolver extends BaseAsyncReturnValueResolver {

    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        return WebAsyncTask.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        return returnValue instanceof WebAsyncTask;
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        asyncSupportRegistry.startCallableProcessing(req, resp, (WebAsyncTask) returnValue);
    }
}

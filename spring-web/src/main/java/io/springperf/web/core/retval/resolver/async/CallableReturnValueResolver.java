package io.springperf.web.core.retval.resolver.async;

import java.util.concurrent.Callable;

import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.async.WebAsyncTask;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

public class CallableReturnValueResolver extends BaseAsyncReturnValueResolver {
    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        return Callable.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        return returnValue instanceof Callable;
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req,
            WebServerHttpResponse resp) throws Exception {
        WebAsyncTask task = new WebAsyncTask<>((Callable) returnValue);
        asyncSupportRegistry.startCallableProcessing(req, resp, task);
    }
}

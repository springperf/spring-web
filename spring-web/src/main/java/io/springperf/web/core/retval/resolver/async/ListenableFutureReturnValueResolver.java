package io.springperf.web.core.retval.resolver.async;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.web.context.request.async.DeferredResult;

public class ListenableFutureReturnValueResolver extends BaseAsyncReturnValueResolver {
    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        return ListenableFuture.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        return returnValue instanceof ListenableFuture;
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        ListenableFuture future = (ListenableFuture) returnValue;
        DeferredResult result = adaptListenableFuture(future);
        asyncSupportRegistry.startDeferredResultProcessing(req, resp, result);
    }

    protected DeferredResult<Object> adaptListenableFuture(ListenableFuture<?> future) {
        DeferredResult<Object> result = new DeferredResult<>();
        future.addCallback(new ListenableFutureCallback<Object>() {
            @Override
            public void onSuccess(@Nullable Object value) {
                result.setResult(value);
            }

            @Override
            public void onFailure(Throwable ex) {
                result.setErrorResult(ex);
            }
        });
        return result;
    }
}

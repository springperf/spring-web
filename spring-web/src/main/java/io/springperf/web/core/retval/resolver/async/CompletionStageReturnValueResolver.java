package io.springperf.web.core.retval.resolver.async;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

public class CompletionStageReturnValueResolver extends BaseAsyncReturnValueResolver {
    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        return CompletionStage.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        return returnValue instanceof CompletionStage;
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        CompletionStage future = (CompletionStage) returnValue;
        DeferredResult result = adaptCompletionStage(future);
        asyncSupportRegistry.startDeferredResultProcessing(req, resp, result);
    }

    private DeferredResult<Object> adaptCompletionStage(CompletionStage<?> future) {
        DeferredResult<Object> result = new DeferredResult<>();
        future.handle((BiFunction<Object, Throwable, Object>) (value, ex) -> {
            if (ex != null) {
                if (ex instanceof CompletionException && ex.getCause() != null) {
                    ex = ex.getCause();
                }
                result.setErrorResult(ex);
            } else {
                result.setResult(value);
            }
            return null;
        });
        return result;
    }
}

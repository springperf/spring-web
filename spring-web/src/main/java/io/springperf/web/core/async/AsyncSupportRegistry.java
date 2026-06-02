package io.springperf.web.core.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.springperf.web.context.WebComponentContainer;
import io.springperf.web.context.WebComponentWrapperUtils;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.pool.BizPoolRegistry;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.json.JacksonConverter;
import io.springperf.web.json.JsonConverter;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.web.context.request.async.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class AsyncSupportRegistry extends WebComponentContainer {
    private List<CallableProcessingInterceptor> callableInterceptors = new ArrayList<>();
    private List<DeferredResultProcessingInterceptor> deferredResultInterceptors = new ArrayList<>();

    private JsonConverter jsonConverter;

    private AsyncTaskExecutor defaultTaskExecutor;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        WebComponentWrapperUtils.registerComponent(this, CallableProcessingInterceptor.class);
        WebComponentWrapperUtils.registerComponent(this, DeferredResultProcessingInterceptor.class);
        ObjectMapper objectMapper = webContext.getBeanFromCtx(ObjectMapper.class);
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }
        jsonConverter = webContext.getWebComponentWithDefault(JsonConverter.class, new JacksonConverter(objectMapper));
    }

    @Override
    public void initComponentPhase1() throws Exception {
        super.initComponentPhase1();
        WebComponentWrapperUtils.initRealComponentList(this, callableInterceptors, CallableProcessingInterceptor.class);
        WebComponentWrapperUtils.initRealComponentList(this, deferredResultInterceptors, DeferredResultProcessingInterceptor.class);
    }

    @Override
    public void initComponentPhase2() throws Exception {
        BizPoolRegistry bizPoolRegistry = webContext.getWebComponent(BizPoolRegistry.class);
        if (bizPoolRegistry != null) {
            ExecutorService defaultPool = bizPoolRegistry.getDefaultPool();
            defaultTaskExecutor = defaultPool != null ? new ConcurrentTaskExecutor(defaultPool) : null;
        }
    }

    public JsonConverter getJsonConverter() {
        return jsonConverter;
    }


    public void startCallableProcessing(WebServerHttpRequest req, WebServerHttpResponse resp, WebAsyncTask<?> webAsyncTask) throws Exception {
        startCallableProcessing(AsyncSupportUtils.getAsyncWebRequest(req, resp), webAsyncTask);
    }

    public void startCallableProcessing(PerfAsyncWebRequest asyncWebRequest, WebAsyncTask<?> webAsyncTask) throws Exception {
        Long timeout = webAsyncTask.getTimeout();
        if (timeout != null) {
            asyncWebRequest.setTimeout(timeout);
        }
        AsyncTaskExecutor executor = webAsyncTask.getExecutor();
        if (executor == null) {
            executor = defaultTaskExecutor;
        }

        Callable<?> callable = webAsyncTask.getCallable();
        WebAsyncSupportUtils.CallableInterceptorChainAdapter interceptorChain = WebAsyncSupportUtils.newCallableInterceptorChain(webAsyncTask, callableInterceptors);

        asyncWebRequest.addTimeoutHandler(() -> {
            Object result = interceptorChain.triggerAfterTimeout(asyncWebRequest, callable);
            if (result != CallableProcessingInterceptor.RESULT_NONE) {
                asyncWebRequest.setConcurrentResultAndDispatch(result);
            }
        });

        asyncWebRequest.addErrorHandler(ex -> {
            if (!asyncWebRequest.isErrorHandlingInProgress()) {
                Object result = interceptorChain.triggerAfterError(asyncWebRequest, callable, ex);
                result = (result != CallableProcessingInterceptor.RESULT_NONE ? result : ex);
                asyncWebRequest.setConcurrentResultAndDispatch(result);
            }
        });

        asyncWebRequest.addCompletionHandler(() -> interceptorChain.triggerAfterCompletion(asyncWebRequest, callable));

        interceptorChain.applyBeforeConcurrentHandling(asyncWebRequest, callable);
        asyncWebRequest.startAsyncProcessing();
        try {
            Future<?> future = executor.submit(() -> {
                Object result = null;
                try {
                    interceptorChain.applyPreProcess(asyncWebRequest, callable);
                    result = callable.call();
                } catch (Throwable ex) {
                    result = ex;
                } finally {
                    result = interceptorChain.applyPostProcess(asyncWebRequest, callable, result);
                }
                asyncWebRequest.setConcurrentResultAndDispatch(result);
            });
            interceptorChain.setTaskFuture(future);
        } catch (RejectedExecutionException ex) {
            Object result = interceptorChain.applyPostProcess(asyncWebRequest, callable, ex);
            asyncWebRequest.setConcurrentResultAndDispatch(result);
            throw ex;
        }
    }

    public void startDeferredResultProcessing(WebServerHttpRequest req, WebServerHttpResponse resp, DeferredResult<?> deferredResult) throws Exception {
        startDeferredResultProcessing(AsyncSupportUtils.getAsyncWebRequest(req, resp), deferredResult);
    }

    public void startDeferredResultProcessing(PerfAsyncWebRequest asyncWebRequest, DeferredResult<?> deferredResult) throws Exception {
        Long timeout = WebAsyncSupportUtils.getDeferredResultTimeout(deferredResult);
        if (timeout != null) {
            asyncWebRequest.setTimeout(timeout);
        }
        WebAsyncSupportUtils.DeferredResultInterceptorChainAdapter interceptorChain = WebAsyncSupportUtils.newDeferredResultInterceptorChain(deferredResult, deferredResultInterceptors);

        asyncWebRequest.addTimeoutHandler(() -> {
            try {
                interceptorChain.triggerAfterTimeout(asyncWebRequest, deferredResult);
            } catch (Throwable ex) {
                asyncWebRequest.setConcurrentResultAndDispatch(ex);
            }
        });

        asyncWebRequest.addErrorHandler(ex -> {
            if (!asyncWebRequest.isErrorHandlingInProgress()) {
                try {
                    if (!interceptorChain.triggerAfterError(asyncWebRequest, deferredResult, ex)) {
                        return;
                    }
                    deferredResult.setErrorResult(ex);
                } catch (Throwable interceptorEx) {
                    asyncWebRequest.setConcurrentResultAndDispatch(interceptorEx);
                }
            }
        });

        asyncWebRequest.addCompletionHandler(() -> interceptorChain.triggerAfterCompletion(asyncWebRequest, deferredResult));

        interceptorChain.applyBeforeConcurrentHandling(asyncWebRequest, deferredResult);
        asyncWebRequest.startAsyncProcessing();
        try {
            interceptorChain.applyPreProcess(asyncWebRequest, deferredResult);
            deferredResult.setResultHandler(result -> {
                result = interceptorChain.applyPostProcess(asyncWebRequest, deferredResult, result);
                asyncWebRequest.setConcurrentResultAndDispatch(result);
            });
        } catch (Throwable ex) {
            asyncWebRequest.setConcurrentResultAndDispatch(ex);
        }
    }
}

package io.springperf.web.core.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebComponentContainer;
import io.springperf.web.context.WebComponentWrapperUtils;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.pool.BizPoolRegistry;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.json.JacksonConverter;
import io.springperf.web.json.JsonConverter;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.web.context.request.async.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

public class AsyncSupportRegistry extends WebComponentContainer {
    private final List<CallableProcessingInterceptor> callableInterceptors = new ArrayList<>();
    private final List<DeferredResultProcessingInterceptor> deferredResultInterceptors = new ArrayList<>();

    private JsonConverter jsonConverter;

    private AsyncTaskExecutor defaultTaskExecutor;

    /** 无 default 业务线程池且方法未显式指定 executor 时的兜底 executor（懒加载复用，避免每次请求新建）。 */
    private volatile AsyncTaskExecutor fallbackExecutor;

    private long defaultTimeout = 30000L; // 30 seconds, matching Spring MVC default

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        this.defaultTimeout = webContext.getProps().getLong(PropertiesConstant.ASYNC_TIMEOUT);
        WebComponentWrapperUtils.registerComponent(this, CallableProcessingInterceptor.class);
        WebComponentWrapperUtils.registerComponent(this, DeferredResultProcessingInterceptor.class);
        ObjectMapper objectMapper = webContext.getBeanFromCtx(ObjectMapper.class);
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }
        jsonConverter = webContext.getWebComponentWithDefault(JsonConverter.class, new JacksonConverter(objectMapper));
    }

    @Override
    public void initComponentPhase2() throws Exception {
        WebComponentWrapperUtils.initRealComponentList(this, callableInterceptors, CallableProcessingInterceptor.class);
        WebComponentWrapperUtils.initRealComponentList(this, deferredResultInterceptors, DeferredResultProcessingInterceptor.class);
        BizPoolRegistry bizPoolRegistry = webContext.getWebComponent(BizPoolRegistry.class);
        if (bizPoolRegistry != null) {
            ExecutorService defaultPool = bizPoolRegistry.getDefaultPool();
            defaultTaskExecutor = defaultPool != null ? new ConcurrentTaskExecutor(defaultPool) : null;
        }
    }

    public void setDefaultTimeout(long defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
    }

    public long getDefaultTimeout() {
        return defaultTimeout;
    }

    public void setTaskExecutor(AsyncTaskExecutor taskExecutor) {
        this.defaultTaskExecutor = taskExecutor;
    }

    /**
     * 懒加载单例的兜底 {@link SimpleAsyncTaskExecutor}。
     * 仅在无 default 业务线程池且方法未显式指定 executor 时使用。
     */
    private AsyncTaskExecutor getOrCreateFallbackExecutor() {
        AsyncTaskExecutor executor = this.fallbackExecutor;
        if (executor == null) {
            synchronized (this) {
                executor = this.fallbackExecutor;
                if (executor == null) {
                    executor = new SimpleAsyncTaskExecutor();
                    this.fallbackExecutor = executor;
                }
            }
        }
        return executor;
    }

    public void addCallableInterceptors(List<CallableProcessingInterceptor> interceptors) {
        this.callableInterceptors.addAll(interceptors);
    }

    public void addDeferredResultInterceptors(List<DeferredResultProcessingInterceptor> interceptors) {
        this.deferredResultInterceptors.addAll(interceptors);
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
        } else {
            asyncWebRequest.setTimeout(defaultTimeout);
        }
        AsyncTaskExecutor executor = webAsyncTask.getExecutor();
        AsyncTaskExecutor effectiveExecutor = executor != null ? executor : defaultTaskExecutor;
        if (effectiveExecutor == null) {
            // 无 default 业务线程池（如 pool.core-pool-size<0 禁用了默认池）且方法未显式指定
            // executor 时兜底为 SimpleAsyncTaskExecutor，避免 NPE（对齐 Spring MVC WebAsyncManager）。
            // 懒加载单例复用，避免每次请求创建新 executor。
            effectiveExecutor = getOrCreateFallbackExecutor();
        }
        final AsyncTaskExecutor executorToUse = effectiveExecutor;

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
        asyncWebRequest.setAsyncReadyCallback(() -> {
            try {
                Future<?> future = executorToUse.submit(() -> {
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
            }
            asyncWebRequest.scheduleTimeoutIfNeeded();
        });
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
        asyncWebRequest.setAsyncReadyCallback(() -> {
            try {
                interceptorChain.applyPreProcess(asyncWebRequest, deferredResult);
                deferredResult.setResultHandler(result -> {
                    result = interceptorChain.applyPostProcess(asyncWebRequest, deferredResult, result);
                    asyncWebRequest.setConcurrentResultAndDispatch(result);
                });
            } catch (Throwable ex) {
                asyncWebRequest.setConcurrentResultAndDispatch(ex);
            }
            asyncWebRequest.scheduleTimeoutIfNeeded();
        });
    }
}

package org.springframework.web.servlet.config.annotation;

import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shim of Spring MVC's {@code AsyncSupportConfigurer}.
 * <p>Collects async configuration from {@link WebMvcConfigurer#configureAsyncSupport(AsyncSupportConfigurer)}
 * and bridges it to the framework's native {@code AsyncSupportRegistry}.
 */
public class AsyncSupportConfigurer {

    private Long timeout;

    private AsyncTaskExecutor taskExecutor;

    private final List<CallableProcessingInterceptor> callableInterceptors = new ArrayList<>();

    private final List<DeferredResultProcessingInterceptor> deferredResultInterceptors = new ArrayList<>();

    public AsyncSupportConfigurer setDefaultTimeout(long timeout) {
        this.timeout = timeout;
        return this;
    }

    public AsyncSupportConfigurer setTaskExecutor(AsyncTaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
        return this;
    }

    public AsyncSupportConfigurer registerCallableInterceptors(CallableProcessingInterceptor... interceptors) {
        this.callableInterceptors.addAll(Arrays.asList(interceptors));
        return this;
    }

    public AsyncSupportConfigurer registerDeferredResultInterceptors(DeferredResultProcessingInterceptor... interceptors) {
        this.deferredResultInterceptors.addAll(Arrays.asList(interceptors));
        return this;
    }

    public Long getTimeout() {
        return timeout;
    }

    public AsyncTaskExecutor getTaskExecutor() {
        return taskExecutor;
    }

    public List<CallableProcessingInterceptor> getCallableInterceptors() {
        return callableInterceptors;
    }

    public List<DeferredResultProcessingInterceptor> getDeferredResultInterceptors() {
        return deferredResultInterceptors;
    }
}
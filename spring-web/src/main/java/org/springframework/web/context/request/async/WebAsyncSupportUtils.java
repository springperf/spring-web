package org.springframework.web.context.request.async;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class WebAsyncSupportUtils {

    private static final CallableProcessingInterceptor timeoutCallableInterceptor = new TimeoutCallableProcessingInterceptor();
    private static final DeferredResultProcessingInterceptor timeoutDeferredResultInterceptor = new TimeoutDeferredResultProcessingInterceptor();

    private static final boolean SUPPORTS_GET_INTERCEPTOR;
    private static final Method GET_INTERCEPTOR_METHOD;

    static {
        Method m = null;
        try {
            m = DeferredResult.class.getMethod("getInterceptor");
        } catch (NoSuchMethodException e) {
            // Spring 6.3+ 移除了 getInterceptor()
        }
        GET_INTERCEPTOR_METHOD = m;
        SUPPORTS_GET_INTERCEPTOR = m != null;
    }

    public static CallableInterceptorChainAdapter newCallableInterceptorChain(WebAsyncTask<?> webAsyncTask, List<CallableProcessingInterceptor> callableInterceptors) {
        List<CallableProcessingInterceptor> interceptors = new ArrayList<>();
        interceptors.add(webAsyncTask.getInterceptor());
        interceptors.addAll(callableInterceptors);
        interceptors.add(timeoutCallableInterceptor);
        return new CallableInterceptorChainAdapter(interceptors);
    }

    public static Long getDeferredResultTimeout(DeferredResult<?> deferredResult) {
        return deferredResult.getTimeoutValue();
    }

    public static DeferredResultInterceptorChainAdapter newDeferredResultInterceptorChain(DeferredResult<?> deferredResult, List<DeferredResultProcessingInterceptor> deferredResultInterceptors) {
        List<DeferredResultProcessingInterceptor> interceptors = new ArrayList<>();
        if (SUPPORTS_GET_INTERCEPTOR) {
            try {
                interceptors.add((DeferredResultProcessingInterceptor) GET_INTERCEPTOR_METHOD.invoke(deferredResult));
            } catch (Exception ignored) {
            }
        }
        interceptors.addAll(deferredResultInterceptors);
        interceptors.add(timeoutDeferredResultInterceptor);
        return new DeferredResultInterceptorChainAdapter(interceptors);
    }

    public static class CallableInterceptorChainAdapter extends CallableInterceptorChain {

        public CallableInterceptorChainAdapter(List<CallableProcessingInterceptor> interceptors) {
            super(interceptors);
        }
    }

    public static class DeferredResultInterceptorChainAdapter extends DeferredResultInterceptorChain {

        public DeferredResultInterceptorChainAdapter(List<DeferredResultProcessingInterceptor> interceptors) {
            super(interceptors);
        }
    }
}

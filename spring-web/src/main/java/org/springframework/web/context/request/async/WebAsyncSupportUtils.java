package org.springframework.web.context.request.async;

import java.util.ArrayList;
import java.util.List;

public class WebAsyncSupportUtils {

    private static final CallableProcessingInterceptor timeoutCallableInterceptor = new TimeoutCallableProcessingInterceptor();
    private static final DeferredResultProcessingInterceptor timeoutDeferredResultInterceptor = new TimeoutDeferredResultProcessingInterceptor();

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
        // 【刻意不移植 master 的「把 DeferredResult 自带拦截器加入链首」——勿当漏改，已实证等价】
        // master 那里是反射：`DeferredResult.class.getMethod("getInterceptor")` + SUPPORTS 探测。
        // 而 getMethod() 只解析 **public** 方法，`DeferredResult#getInterceptor()` 在 Spring 5.3.31 与
        // 6.1.15 上均为**包级可见**（javap -p 实测：`final ... DeferredResultProcessingInterceptor getInterceptor();`，
        // 无修饰符）→ master 的探测恒为 false，其运行期**同样不会**把该拦截器加入链。
        // 即「文本差异、行为等价」。本分支与 DeferredResult 同包、本可直连，但直连会改变行为：
        // Spring 默认拦截器会让 triggerAfterError 提前返回 false，从而使
        // `AsyncSupportRegistryDetailsTest#startDeferredResultProcessing_errorHandlerThrows_dispatchesInterceptorError`
        // 期望的 setConcurrentResultAndDispatch 不再发生（实测）。故本分支保持不加。
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

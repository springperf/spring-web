package io.springperf.web.core.async;

import io.springperf.web.context.WebContext;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncTask;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 补充 AsyncSupportRegistry 覆盖率：setters/拦截器注册、req/resp 重载、
 * callable 超时/错误/提交异常分支、deferred 超时/错误异步回调分支。
 */
@ExtendWith(MockitoExtension.class)
class AsyncSupportRegistryDetailsTest {

    AsyncSupportRegistry registry;

    @Mock PerfAsyncWebRequest asyncWebRequest;

    private Runnable timeoutHandler;
    private Consumer<Throwable> errorHandler;
    private Runnable completionHandler;
    private Runnable asyncReadyCallback;

    @BeforeEach
    void setUp() {
        registry = new AsyncSupportRegistry();
        timeoutHandler = null;
        errorHandler = null;
        completionHandler = null;
        asyncReadyCallback = null;
        lenient().doAnswer(inv -> { timeoutHandler = inv.getArgument(0); return null; })
                .when(asyncWebRequest).addTimeoutHandler(any(Runnable.class));
        lenient().doAnswer(inv -> { errorHandler = inv.getArgument(0); return null; })
                .when(asyncWebRequest).addErrorHandler(any(Consumer.class));
        lenient().doAnswer(inv -> { completionHandler = inv.getArgument(0); return null; })
                .when(asyncWebRequest).addCompletionHandler(any(Runnable.class));
        lenient().doAnswer(inv -> { asyncReadyCallback = inv.getArgument(0); return null; })
                .when(asyncWebRequest).setAsyncReadyCallback(any(Runnable.class));
        lenient().when(asyncWebRequest.isErrorHandlingInProgress()).thenReturn(false);
    }

    /* ==================== setters / 拦截器注册 ==================== */

    @Test
    void setDefaultTimeout_getDefaultTimeout_roundTrip() {
        registry.setDefaultTimeout(12345L);
        assertEquals(12345L, registry.getDefaultTimeout());
    }

    @Test
    void setTaskExecutor_usedForCallableWithoutExecutor() throws Exception {
        AsyncTaskExecutor inlineExecutor = new AsyncTaskExecutor() {
            @Override public void execute(Runnable task, long startTimeout) { task.run(); }
            @Override public void execute(Runnable task) { task.run(); }
            @Override public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
                try { task.call(); } catch (Exception e) { throw new RuntimeException(e); }
                return null;
            }
        };
        registry.setTaskExecutor(inlineExecutor);
        WebAsyncTask<String> task = new WebAsyncTask<>(() -> "done");

        registry.startCallableProcessing(asyncWebRequest, task);

        assertNotNull(asyncReadyCallback);
        asyncReadyCallback.run();
        verify(asyncWebRequest).setConcurrentResultAndDispatch("done");
    }

    @Test
    void addCallableInterceptors_appendsToList() throws Exception {
        CallableProcessingInterceptor interceptor = new CallableProcessingInterceptor() {};
        registry.addCallableInterceptors(Collections.singletonList(interceptor));
        List<CallableProcessingInterceptor> list = readField("callableInterceptors");
        assertEquals(1, list.size());
        assertSame(interceptor, list.get(0));
    }

    @Test
    void addDeferredResultInterceptors_appendsToList() throws Exception {
        DeferredResultProcessingInterceptor interceptor = new DeferredResultProcessingInterceptor() {};
        registry.addDeferredResultInterceptors(Collections.singletonList(interceptor));
        List<DeferredResultProcessingInterceptor> list = readField("deferredResultInterceptors");
        assertEquals(1, list.size());
        assertSame(interceptor, list.get(0));
    }

    @SuppressWarnings("unchecked")
    private <T> T readField(String name) throws Exception {
        java.lang.reflect.Field field = AsyncSupportRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(registry);
    }

    /* ==================== req/resp 重载 ==================== */

    @Test
    void startCallableProcessing_overloadWithReqResp_usesAsyncWebRequest() throws Exception {
        RequestContext reqCtx = new MapRequestContext();
        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        when(request.getRequestContext()).thenReturn(reqCtx);
        WebContext wc = mock(WebContext.class);
        lenient().when(request.getWebContext()).thenReturn(wc);
        lenient().when(wc.getDispatcherHandler()).thenReturn(mock(io.springperf.web.core.DispatcherHandler.class));

        WebAsyncTask<String> task = new WebAsyncTask<>(() -> "ok");

        registry.startCallableProcessing(request, response, task);

        // 内部已创建并注册 PerfAsyncWebRequest 到请求上下文
        assertNotNull(reqCtx.getAttribute(AsyncSupportUtils.WEB_ASYNC_REQUEST_ATTRIBUTE));
    }

    @Test
    void startDeferredResultProcessing_overloadWithReqResp_usesAsyncWebRequest() throws Exception {
        RequestContext reqCtx = new MapRequestContext();
        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        when(request.getRequestContext()).thenReturn(reqCtx);
        WebContext wc = mock(WebContext.class);
        lenient().when(request.getWebContext()).thenReturn(wc);
        lenient().when(wc.getDispatcherHandler()).thenReturn(mock(io.springperf.web.core.DispatcherHandler.class));

        DeferredResult<String> deferredResult = new DeferredResult<>(5000L);

        registry.startDeferredResultProcessing(request, response, deferredResult);

        assertNotNull(reqCtx.getAttribute(AsyncSupportUtils.WEB_ASYNC_REQUEST_ATTRIBUTE));
    }

    /* ==================== callable：超时/错误 handler ==================== */

    @Test
    void startCallableProcessing_timeoutHandlerWithInterceptorResult_dispatches() throws Exception {
        CallableProcessingInterceptor returningInterceptor = new CallableProcessingInterceptor() {
            @Override
            public <T> Object handleTimeout(org.springframework.web.context.request.NativeWebRequest request,
                                            java.util.concurrent.Callable<T> task) {
                return "timeout-result";
            }
        };
        registry.addCallableInterceptors(Collections.singletonList(returningInterceptor));
        WebAsyncTask<String> task = new WebAsyncTask<>(() -> "ok");

        registry.startCallableProcessing(asyncWebRequest, task);

        assertNotNull(timeoutHandler);
        timeoutHandler.run();
        verify(asyncWebRequest).setConcurrentResultAndDispatch("timeout-result");
    }

    @Test
    void startCallableProcessing_errorHandlerWithInterceptorResult_dispatches() throws Exception {
        CallableProcessingInterceptor returningInterceptor = new CallableProcessingInterceptor() {
            @Override
            public <T> Object handleError(org.springframework.web.context.request.NativeWebRequest request,
                                          java.util.concurrent.Callable<T> task,
                                          Throwable error) {
                return "error-result";
            }
        };
        registry.addCallableInterceptors(Collections.singletonList(returningInterceptor));
        WebAsyncTask<String> task = new WebAsyncTask<>(() -> "ok");
        registry.startCallableProcessing(asyncWebRequest, task);

        assertNotNull(errorHandler);
        errorHandler.accept(new RuntimeException("boom"));
        verify(asyncWebRequest).setConcurrentResultAndDispatch("error-result");
    }

    @Test
    void startCallableProcessing_errorHandlerWithNoInterceptorResult_dispatchesOriginalError() throws Exception {
        WebAsyncTask<String> task = new WebAsyncTask<>(() -> "ok");
        registry.startCallableProcessing(asyncWebRequest, task);

        RuntimeException boom = new RuntimeException("boom");
        errorHandler.accept(boom);
        verify(asyncWebRequest).setConcurrentResultAndDispatch(boom);
    }

    @Test
    void startCallableProcessing_asyncReadyCallableThrows_dispatchesThrowable() throws Exception {
        AsyncTaskExecutor inline = inlineExecutor();
        registry.setTaskExecutor(inline);
        WebAsyncTask<String> task = new WebAsyncTask<>(() -> {
            throw new IllegalStateException("call-failed");
        });

        registry.startCallableProcessing(asyncWebRequest, task);

        assertNotNull(asyncReadyCallback);
        asyncReadyCallback.run();
        verify(asyncWebRequest).setConcurrentResultAndDispatch(any(IllegalStateException.class));
    }

    @Test
    void startCallableProcessing_asyncReadySubmitRejected_dispatchesRejectedExecution() throws Exception {
        AsyncTaskExecutor rejecting = new AsyncTaskExecutor() {
            @Override public void execute(Runnable task, long startTimeout) {
                throw new RejectedExecutionException("rejected");
            }
            @Override public void execute(Runnable task) { throw new RejectedExecutionException("rejected"); }
            @Override public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
                throw new RejectedExecutionException("rejected");
            }
        };
        registry.setTaskExecutor(rejecting);
        WebAsyncTask<String> task = new WebAsyncTask<>(() -> "ok");

        registry.startCallableProcessing(asyncWebRequest, task);

        assertNotNull(asyncReadyCallback);
        asyncReadyCallback.run();
        verify(asyncWebRequest).setConcurrentResultAndDispatch(any(RejectedExecutionException.class));
    }

    /* ==================== deferred：超时/错误 handler ==================== */

    @Test
    void startDeferredResultProcessing_timeoutHandlerThrows_dispatchesThrowable() throws Exception {
        DeferredResultProcessingInterceptor throwingInterceptor = new DeferredResultProcessingInterceptor() {
            @Override
            public <T> boolean handleTimeout(org.springframework.web.context.request.NativeWebRequest request,
                                             org.springframework.web.context.request.async.DeferredResult<T> result) {
                throw new IllegalStateException("timeout-failed");
            }
        };
        registry.addDeferredResultInterceptors(Collections.singletonList(throwingInterceptor));
        DeferredResult<String> deferredResult = new DeferredResult<>(1000L);

        registry.startDeferredResultProcessing(asyncWebRequest, deferredResult);

        assertNotNull(timeoutHandler);
        timeoutHandler.run();
        verify(asyncWebRequest).setConcurrentResultAndDispatch(any(IllegalStateException.class));
    }

    @Test
    void startDeferredResultProcessing_successfulResultHandler_dispatches() throws Exception {
        DeferredResult<String> deferredResult = new DeferredResult<>();
        registry.startDeferredResultProcessing(asyncWebRequest, deferredResult);

        assertNotNull(asyncReadyCallback);
        asyncReadyCallback.run();
        deferredResult.setResult("final");
        verify(asyncWebRequest).setConcurrentResultAndDispatch("final");
    }

    @Test
    void startDeferredResultProcessing_errorHandlerSetErrorResult() throws Exception {
        DeferredResultProcessingInterceptor continuingInterceptor = new DeferredResultProcessingInterceptor() {
            @Override
            public <T> boolean handleError(org.springframework.web.context.request.NativeWebRequest request,
                                           org.springframework.web.context.request.async.DeferredResult<T> result,
                                           Throwable error) {
                return true;
            }
        };
        registry.addDeferredResultInterceptors(Collections.singletonList(continuingInterceptor));
        DeferredResult<String> deferredResult = new DeferredResult<>();
        registry.startDeferredResultProcessing(asyncWebRequest, deferredResult);

        assertNotNull(errorHandler);
        RuntimeException boom = new RuntimeException("deferred-failed");
        errorHandler.accept(boom);
        // handleError=true → deferredResult.setErrorResult(error)，结果可被读取
        assertNotNull(deferredResult.getResult(), "error result should be stored in the deferred result");
    }

    @Test
    void startDeferredResultProcessing_errorHandlerThrows_dispatchesInterceptorError() throws Exception {
        DeferredResultProcessingInterceptor throwingInterceptor = new DeferredResultProcessingInterceptor() {
            @Override
            public <T> boolean handleError(org.springframework.web.context.request.NativeWebRequest request,
                                           org.springframework.web.context.request.async.DeferredResult<T> result,
                                           Throwable error) {
                throw new IllegalStateException("interceptor-error");
            }
        };
        registry.addDeferredResultInterceptors(Collections.singletonList(throwingInterceptor));
        DeferredResult<String> deferredResult = new DeferredResult<>();
        registry.startDeferredResultProcessing(asyncWebRequest, deferredResult);

        errorHandler.accept(new RuntimeException("boom"));
        verify(asyncWebRequest).setConcurrentResultAndDispatch(any(IllegalStateException.class));
    }

    @Test
    void startDeferredResultProcessing_readyCallbackPreProcessThrows_dispatches() throws Exception {
        DeferredResultProcessingInterceptor throwingPreProcess = new DeferredResultProcessingInterceptor() {
            @Override
            public <T> void preProcess(org.springframework.web.context.request.NativeWebRequest request,
                                       org.springframework.web.context.request.async.DeferredResult<T> result) {
                throw new IllegalStateException("pre-failed");
            }
        };
        registry.addDeferredResultInterceptors(Collections.singletonList(throwingPreProcess));
        DeferredResult<String> deferredResult = new DeferredResult<>();
        registry.startDeferredResultProcessing(asyncWebRequest, deferredResult);

        assertNotNull(asyncReadyCallback);
        asyncReadyCallback.run();
        verify(asyncWebRequest).setConcurrentResultAndDispatch(any(IllegalStateException.class));
    }

    /* ==================== helpers ==================== */

    private static AsyncTaskExecutor inlineExecutor() {
        return new AsyncTaskExecutor() {
            @Override public void execute(Runnable task, long startTimeout) { task.run(); }
            @Override public void execute(Runnable task) { task.run(); }
            @Override public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
                try { task.call(); } catch (Exception e) { throw new RuntimeException(e); }
                return null;
            }
        };
    }

    private static final class MapRequestContext implements RequestContext {
        final Map<String, Object> attrs = new HashMap<>();
        final Map<RequestAttribute<?>, Object> typed = new HashMap<>();

        @Override public Map<String, Object> getAttributes() { return attrs; }
        @Override public Object getAttribute(String name) { return attrs.get(name); }
        @Override public void setAttribute(String name, Object o) { attrs.put(name, o); }
        @Override public Object removeAttribute(String name) { return attrs.remove(name); }
        @Override public <T> T getAttribute(RequestAttribute<T> key) { return (T) typed.get(key); }
        @Override public <T> void setAttribute(RequestAttribute<T> key, T value) { typed.put(key, value); }
    }
}
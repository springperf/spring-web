package io.springperf.web.core.retval.resolver.async;

import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.SettableListenableFuture;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncTask;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AsyncReturnValueResolversTest {

    @Mock
    AsyncSupportRegistry asyncSupportRegistry;

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    private <T extends BaseAsyncReturnValueResolver> void initAsyncSupport(T resolver) throws Exception {
        Field f = BaseAsyncReturnValueResolver.class.getDeclaredField("asyncSupportRegistry");
        f.setAccessible(true);
        f.set(resolver, asyncSupportRegistry);
    }

    // ==================== DeferredResultReturnValueResolver ====================

    @Test
    void deferredResult_supportsReturnType() throws Exception {
        DeferredResultReturnValueResolver r = new DeferredResultReturnValueResolver();
        assertTrue(r.supportsReturnType(param("deferredResultParam", DeferredResult.class), null));
    }

    @Test
    void deferredResult_notSupportsOtherType() throws Exception {
        DeferredResultReturnValueResolver r = new DeferredResultReturnValueResolver();
        assertFalse(r.supportsReturnType(param("stringParam", String.class), null));
    }

    @Test
    void deferredResult_supportsReturnValue() {
        DeferredResultReturnValueResolver r = new DeferredResultReturnValueResolver();
        assertTrue(r.supportsReturnValue(new DeferredResult<>(), null, null));
    }

    @Test
    void deferredResult_resolve_delegatesToRegistry() throws Exception {
        initAsyncSupport(new DeferredResultReturnValueResolver());
        DeferredResultReturnValueResolver r = new DeferredResultReturnValueResolver();
        initAsyncSupport(r);
        DeferredResult<?> result = new DeferredResult<>();

        r.resolveReturnValue(result, null, request, response);

        verify(asyncSupportRegistry).startDeferredResultProcessing(request, response, result);
    }

    // ==================== AsyncTaskReturnValueResolver ====================

    @Test
    void asyncTask_supportsReturnType() throws Exception {
        AsyncTaskReturnValueResolver r = new AsyncTaskReturnValueResolver();
        assertTrue(r.supportsReturnType(param("webAsyncTaskParam", WebAsyncTask.class), null));
    }

    @Test
    void asyncTask_supportsReturnValue() {
        AsyncTaskReturnValueResolver r = new AsyncTaskReturnValueResolver();
        assertTrue(r.supportsReturnValue(new WebAsyncTask<>(() -> ""), null, null));
    }

    @Test
    void asyncTask_resolve_delegatesToRegistry() throws Exception {
        AsyncTaskReturnValueResolver r = new AsyncTaskReturnValueResolver();
        initAsyncSupport(r);
        WebAsyncTask<?> task = new WebAsyncTask<>(() -> "");

        r.resolveReturnValue(task, null, request, response);

        verify(asyncSupportRegistry).startCallableProcessing(request, response, task);
    }

    // ==================== CallableReturnValueResolver ====================

    @Test
    void callable_supportsReturnType() throws Exception {
        CallableReturnValueResolver r = new CallableReturnValueResolver();
        assertTrue(r.supportsReturnType(param("callableParam", Callable.class), null));
    }

    @Test
    void callable_supportsReturnValue() {
        CallableReturnValueResolver r = new CallableReturnValueResolver();
        assertTrue(r.supportsReturnValue((Callable<String>) () -> "ok", null, null));
    }

    @Test
    void callable_resolve_delegatesToRegistry() throws Exception {
        CallableReturnValueResolver r = new CallableReturnValueResolver();
        initAsyncSupport(r);
        Callable<String> task = () -> "ok";

        r.resolveReturnValue(task, null, request, response);

        verify(asyncSupportRegistry).startCallableProcessing(eq(request), eq(response), any(WebAsyncTask.class));
    }

    // ==================== ListenableFutureReturnValueResolver ====================

    @Test
    void listenableFuture_supportsReturnType() throws Exception {
        ListenableFutureReturnValueResolver r = new ListenableFutureReturnValueResolver();
        assertTrue(r.supportsReturnType(param("listenableFutureParam", ListenableFuture.class), null));
    }

    @Test
    void listenableFuture_supportsReturnValue() {
        ListenableFutureReturnValueResolver r = new ListenableFutureReturnValueResolver();
        assertTrue(r.supportsReturnValue(new SettableListenableFuture<>(), null, null));
    }

    @Test
    void listenableFuture_resolve_delegatesToRegistry() throws Exception {
        ListenableFutureReturnValueResolver r = new ListenableFutureReturnValueResolver();
        initAsyncSupport(r);
        SettableListenableFuture<String> future = new SettableListenableFuture<>();

        r.resolveReturnValue(future, null, request, response);

        verify(asyncSupportRegistry).startDeferredResultProcessing(eq(request), eq(response), any(DeferredResult.class));
    }

    @Test
    void listenableFuture_adapt_onSuccess_setsResult() throws Exception {
        ListenableFutureReturnValueResolver r = new ListenableFutureReturnValueResolver();
        SettableListenableFuture<String> future = new SettableListenableFuture<>();

        DeferredResult<Object> result = r.adaptListenableFuture(future);
        future.set("success");

        assertEquals("success", result.getResult());
    }

    @Test
    void listenableFuture_adapt_onFailure_setsErrorResult() throws Exception {
        ListenableFutureReturnValueResolver r = new ListenableFutureReturnValueResolver();
        SettableListenableFuture<String> future = new SettableListenableFuture<>();

        DeferredResult<Object> result = r.adaptListenableFuture(future);
        IllegalStateException error = new IllegalStateException("failed");
        future.setException(error);

        assertSame(error, result.getResult());
    }

    // ==================== CompletionStageReturnValueResolver ====================

    @Test
    void completionStage_supportsReturnType() throws Exception {
        CompletionStageReturnValueResolver r = new CompletionStageReturnValueResolver();
        assertTrue(r.supportsReturnType(param("completionStageParam", CompletableFuture.class), null));
    }

    @Test
    void completionStage_supportsReturnValue() {
        CompletionStageReturnValueResolver r = new CompletionStageReturnValueResolver();
        assertTrue(r.supportsReturnValue(new CompletableFuture<>(), null, null));
    }

    @Test
    void completionStage_resolve_delegatesToRegistry() throws Exception {
        CompletionStageReturnValueResolver r = new CompletionStageReturnValueResolver();
        initAsyncSupport(r);
        CompletableFuture<String> future = new CompletableFuture<>();

        r.resolveReturnValue(future, null, request, response);

        verify(asyncSupportRegistry).startDeferredResultProcessing(eq(request), eq(response), any(DeferredResult.class));
    }

    // ==================== parameter helpers ====================

    private MethodParameter param(String methodName, Class<?> paramType) throws Exception {
        for (Method m : getClass().getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterCount() == 1) {
                return new MethodParameter(m, 0);
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    @SuppressWarnings("unused")
    public void deferredResultParam(DeferredResult<?> d) {}
    @SuppressWarnings("unused")
    public void webAsyncTaskParam(WebAsyncTask<?> t) {}
    @SuppressWarnings("unused")
    public void callableParam(Callable<?> c) {}
    @SuppressWarnings("unused")
    public void listenableFutureParam(ListenableFuture<?> f) {}
    @SuppressWarnings("unused")
    public void completionStageParam(CompletableFuture<?> f) {}
    @SuppressWarnings("unused")
    public void stringParam(String s) {}
}
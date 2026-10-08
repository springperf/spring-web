package io.springperf.web.core.async;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

@ExtendWith(MockitoExtension.class)
class PerfAsyncWebRequestTest {

    @Mock
    WebServerHttpRequest request;
    @Mock
    WebServerHttpResponse response;
    @Mock
    WebContext webContext;
    @Mock
    DispatcherHandler dispatcherHandler;

    PerfAsyncWebRequest asyncWebRequest;

    @BeforeEach
    void setUp() {
        asyncWebRequest = new PerfAsyncWebRequest(request, response);
    }

    @Test
    void startAsync_transitionsToStarted() {
        asyncWebRequest.startAsync();
        assertTrue(asyncWebRequest.isAsyncStarted());
        verify(response).addWriteRespEventListener(asyncWebRequest);
    }

    @Test
    void startAsync_whenAlreadyStarted_throwsISE() {
        asyncWebRequest.startAsync();
        assertThrows(IllegalStateException.class, () -> asyncWebRequest.startAsync());
    }

    /**
     * 计量配对：每个异步生命周期恰好 +1 / -1。第二半（重复终结不得二次递减）才是要点——递减没有幂等守卫的话 计数会变成负值，而负值与残留叠加仍可能读成 0，把真泄漏掩盖掉。
     */
    @Test
    void asyncLifecycle_isCountedOncePerLifecycle_andReleasedOnce() {
        io.springperf.web.core.metrics.CountingWebMetrics metrics = new io.springperf.web.core.metrics.CountingWebMetrics();
        when(request.getWebContext()).thenReturn(webContext);
        when(webContext.getWebComponent(io.springperf.web.core.metrics.WebMetrics.class)).thenReturn(metrics);

        PerfAsyncWebRequest asyncRequest = new PerfAsyncWebRequest(request, response);
        assertEquals(0, metrics.activeAsyncLifecycles(), "未启动异步时不应计入");

        asyncRequest.startAsync();
        assertEquals(1, metrics.activeAsyncLifecycles(), "startAsync 应 +1");

        asyncRequest.completeSuccessCallback();
        assertEquals(0, metrics.activeAsyncLifecycles(), "写终结应 -1");

        asyncRequest.completeSuccessCallback();
        assertEquals(0, metrics.activeAsyncLifecycles(), "重复终结不得二次递减");
    }

    @Test
    void startAsync_withTimeoutAndHandler_schedulesTimeout() {
        asyncWebRequest.setTimeout(1000L);
        asyncWebRequest.addTimeoutHandler(() -> {
        });
        asyncWebRequest.startAsync();
        asyncWebRequest.scheduleTimeoutIfNeeded();
        verify(response).setTimeout(any(Runnable.class), eq(1000L));
    }

    @Test
    void startAsync_nonPositiveTimeout_doesNotScheduleTimeout() {
        asyncWebRequest.setTimeout(-1L);
        asyncWebRequest.addTimeoutHandler(() -> {
        });
        asyncWebRequest.startAsync();
        asyncWebRequest.scheduleTimeoutIfNeeded();
        verify(response, never()).setTimeout(any(), anyLong());
    }

    @Test
    void startAsync_noTimeoutHandler_doesNotScheduleTimeout() {
        asyncWebRequest.setTimeout(1000L);
        asyncWebRequest.startAsync();
        asyncWebRequest.scheduleTimeoutIfNeeded();
        verify(response, never()).setTimeout(any(), anyLong());
    }

    @Test
    void dispatch_transitionsToDispatched() {
        when(request.getWebContext()).thenReturn(webContext);
        when(webContext.getDispatcherHandler()).thenReturn(dispatcherHandler);
        asyncWebRequest.startAsync();
        asyncWebRequest.dispatch();
        assertFalse(asyncWebRequest.isAsyncStarted());
        verify(dispatcherHandler).asyncDispatch(same(request), same(response), any());
    }

    @Test
    void dispatch_whenNotStarted_isNoOp() {
        asyncWebRequest.dispatch();
        verify(request, never()).getWebContext();
    }

    @Test
    void dispatch_whenAlreadyCompleted_isNoOp() {
        asyncWebRequest.startAsync();
        asyncWebRequest.completeSuccessCallback();
        // 生命周期开关（startAsync / 写终结）本来就会解析计量组件 → 也就会查一次 context；那不是本用例要断言的
        // 东西（用例名说的是 dispatch 是 no-op）。故把验证范围收到 dispatch() 这一次调用上。
        clearInvocations(request);
        asyncWebRequest.dispatch();
        verify(request, never()).getWebContext();
    }

    @Test
    void completeSuccessCallback_transitionsToCompleted() {
        Runnable completionHandler = mock(Runnable.class);
        asyncWebRequest.addCompletionHandler(completionHandler);
        asyncWebRequest.completeSuccessCallback();
        assertTrue(asyncWebRequest.isAsyncComplete());
        verify(completionHandler).run();
    }

    @Test
    void completeSuccessCallback_withoutHandler_doesNotThrow() {
        asyncWebRequest.completeSuccessCallback();
        assertTrue(asyncWebRequest.isAsyncComplete());
    }

    @Test
    void completeErrorCallback_transitionsToCompleted() {
        Consumer<Throwable> errorHandler = mock(Consumer.class);
        asyncWebRequest.addErrorHandler(errorHandler);
        RuntimeException ex = new RuntimeException("test error");
        asyncWebRequest.completeErrorCallback(ex);
        assertTrue(asyncWebRequest.isAsyncComplete());
        verify(errorHandler).accept(ex);
    }

    @Test
    void completeErrorCallback_withoutHandler_doesNotThrow() {
        asyncWebRequest.completeErrorCallback(new RuntimeException("test"));
        assertTrue(asyncWebRequest.isAsyncComplete());
    }

    @Test
    void setConcurrentResultAndDispatch_dispatches() {
        when(request.getWebContext()).thenReturn(webContext);
        when(webContext.getDispatcherHandler()).thenReturn(dispatcherHandler);
        asyncWebRequest.startAsync();
        asyncWebRequest.setConcurrentResultAndDispatch("result");
        verify(dispatcherHandler).asyncDispatch(same(request), same(response), eq("result"));
        assertFalse(asyncWebRequest.isErrorHandlingInProgress());
    }

    @Test
    void setConcurrentResultAndDispatch_withThrowable_setsErrorHandling() {
        when(request.getWebContext()).thenReturn(webContext);
        when(webContext.getDispatcherHandler()).thenReturn(dispatcherHandler);
        asyncWebRequest.startAsync();
        RuntimeException ex = new RuntimeException("error");
        asyncWebRequest.setConcurrentResultAndDispatch(ex);
        assertTrue(asyncWebRequest.isErrorHandlingInProgress());
        verify(dispatcherHandler).asyncDispatch(same(request), same(response), same(ex));
    }

    @Test
    void setConcurrentResultAndDispatch_whenAlreadySet_isNoOp() {
        when(request.getWebContext()).thenReturn(webContext);
        when(webContext.getDispatcherHandler()).thenReturn(dispatcherHandler);
        asyncWebRequest.startAsync();
        asyncWebRequest.setConcurrentResultAndDispatch("first");
        asyncWebRequest.setConcurrentResultAndDispatch("second");
        verify(dispatcherHandler, times(1)).asyncDispatch(same(request), same(response), eq("first"));
    }

    @Test
    void setConcurrentResultAndDispatch_whenAsyncComplete_doesNotDispatch() {
        asyncWebRequest.startAsync();
        asyncWebRequest.completeSuccessCallback();
        // 同上：计量解析发生在生命周期开关上，本用例断言的是「已完成时不再分发」，故只验证这一句调用。
        clearInvocations(request);
        asyncWebRequest.setConcurrentResultAndDispatch("result");
        verify(request, never()).getWebContext();
    }

    @Test
    void start_asyncStarted() {
        asyncWebRequest.start();
        assertTrue(asyncWebRequest.isAsyncStarted());
    }

    @Test
    void start_withTimeout_schedulesTimeout() {
        Runnable timeoutHandler = mock(Runnable.class);
        asyncWebRequest.addTimeoutHandler(timeoutHandler);
        asyncWebRequest.start(5000L);
        asyncWebRequest.scheduleTimeoutIfNeeded();
        assertTrue(asyncWebRequest.isAsyncStarted());
        verify(response).setTimeout(any(Runnable.class), eq(5000L));
    }

    @Test
    void timeoutHandler_executesWhenTimeoutFires() {
        Runnable timeoutHandler = mock(Runnable.class);
        asyncWebRequest.addTimeoutHandler(timeoutHandler);
        asyncWebRequest.setTimeout(100L);
        asyncWebRequest.startAsync();
        asyncWebRequest.scheduleTimeoutIfNeeded();
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(response).setTimeout(captor.capture(), eq(100L));
        captor.getValue().run();
        verify(timeoutHandler).run();
        // 回归 R3-A5：超时回调不占用状态——修复前先 CAS 到 COMPLETED，timeoutHandler 内的
        // setConcurrentResultAndDispatch 因 isAsyncComplete() 直接 return，超时结果被丢弃、响应悬挂。
        // 修复后状态保持 ASYNC_STARTED，由 setConcurrentResultAndDispatch 走正常 dispatch 推进完成。
        assertFalse(asyncWebRequest.isAsyncComplete());
    }

    @Test
    void timeoutFires_thenSetConcurrentResultAndDispatch_dispatches() {
        when(request.getWebContext()).thenReturn(webContext);
        when(webContext.getDispatcherHandler()).thenReturn(dispatcherHandler);
        asyncWebRequest.addTimeoutHandler(
                () -> asyncWebRequest.setConcurrentResultAndDispatch(new RuntimeException("timeout")));
        asyncWebRequest.setTimeout(100L);
        asyncWebRequest.startAsync();
        asyncWebRequest.scheduleTimeoutIfNeeded();
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(response).setTimeout(captor.capture(), eq(100L));
        captor.getValue().run();
        // 超时结果经正常 dispatch 路径到达 dispatcher（修复前被 COMPLETED 状态短路，永远到不了）
        verify(dispatcherHandler).asyncDispatch(same(request), same(response), any(Throwable.class));
        assertTrue(asyncWebRequest.isErrorHandlingInProgress());
    }

    @Test
    void timeoutHandler_whenAlreadyCompleted_doesNotRunHandler() {
        Runnable timeoutHandler = mock(Runnable.class);
        asyncWebRequest.addTimeoutHandler(timeoutHandler);
        asyncWebRequest.setTimeout(100L);
        asyncWebRequest.startAsync();
        asyncWebRequest.scheduleTimeoutIfNeeded();
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(response).setTimeout(captor.capture(), eq(100L));
        asyncWebRequest.completeSuccessCallback();
        captor.getValue().run();
        verify(timeoutHandler, never()).run();
    }

    @Test
    void writeStreamSuccessCallback_runsHandlerWithNull() {
        Consumer<Throwable> callback = mock(Consumer.class);
        asyncWebRequest.addWriteCallbackHandler(callback);
        asyncWebRequest.writeStreamSuccessCallback();
        verify(callback).accept(null);
    }

    @Test
    void writeStreamErrorCallback_runsHandlerWithThrowable() {
        Consumer<Throwable> callback = mock(Consumer.class);
        asyncWebRequest.addWriteCallbackHandler(callback);
        asyncWebRequest.writeStreamErrorCallback(new RuntimeException("write error"));
        verify(callback).accept(any(RuntimeException.class));
    }

    @Test
    void writeStreamErrorCallback_nullThrowable_usesDefault() {
        Consumer<Throwable> callback = mock(Consumer.class);
        asyncWebRequest.addWriteCallbackHandler(callback);
        asyncWebRequest.writeStreamErrorCallback(null);
        verify(callback).accept(any(PerfAsyncWebRequest.DefaultWriteErrorException.class));
    }

    @Test
    void writeStreamErrorCallback_withoutHandler_doesNotThrow() {
        asyncWebRequest.writeStreamErrorCallback(new RuntimeException());
    }

    @Test
    void isAsyncStarted_newState_returnsFalse() {
        assertFalse(asyncWebRequest.isAsyncStarted());
    }

    @Test
    void isAsyncStarted_afterDispatch_returnsFalse() {
        when(request.getWebContext()).thenReturn(webContext);
        when(webContext.getDispatcherHandler()).thenReturn(dispatcherHandler);
        asyncWebRequest.startAsync();
        asyncWebRequest.dispatch();
        assertFalse(asyncWebRequest.isAsyncStarted());
    }

    @Test
    void isAsyncComplete_afterComplete_returnsTrue() {
        asyncWebRequest.completeSuccessCallback();
        assertTrue(asyncWebRequest.isAsyncComplete());
    }

    @Test
    void isStarted_newState_returnsFalse() {
        assertFalse(asyncWebRequest.isStarted());
    }

    @Test
    void isStarted_afterComplete_returnsTrue() {
        asyncWebRequest.completeSuccessCallback();
        assertTrue(asyncWebRequest.isStarted());
    }

    @Test
    void isCompleted_newState_returnsFalse() {
        assertFalse(asyncWebRequest.isCompleted());
    }

    @Test
    void isCompleted_afterComplete_returnsTrue() {
        asyncWebRequest.completeSuccessCallback();
        assertTrue(asyncWebRequest.isCompleted());
    }

    @Test
    void complete_callsCompleteSuccessCallback() {
        Runnable completionHandler = mock(Runnable.class);
        asyncWebRequest.addCompletionHandler(completionHandler);
        asyncWebRequest.complete();
        assertTrue(asyncWebRequest.isAsyncComplete());
        verify(completionHandler).run();
    }

    @Test
    void defaultWriteErrorException_doesNotFillInStackTrace() {
        PerfAsyncWebRequest.DefaultWriteErrorException ex = new PerfAsyncWebRequest.DefaultWriteErrorException();
        assertSame(ex, ex.fillInStackTrace());
    }

    /**
     * 并发断连信号下，取消钩子至多执行一次，入站请求引用也只归还一次。
     * <p>
     * 原先 {@code 读 → 判非空 → 置 null} 三步非原子（且字段非 volatile）：EventLoop 上重复到达的断连信号会让两个线程 同时读到同一个 handler 并各跑一次 ——
     * 上游被重复取消，引用计数则可能重复释放。
     * </p>
     */
    @Test
    void releaseOnConnectionClose_concurrentSignals_runHandlerAndReleaseOnce() throws Exception {
        Runnable closeHandler = mock(Runnable.class);
        asyncWebRequest.addConnectionCloseHandler(closeHandler);
        asyncWebRequest.startAsync();

        int n = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 100; j++) {
                        asyncWebRequest.releaseOnConnectionClose();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发任务未在时限内结束");

        verify(closeHandler, times(1)).run();
        verify(request, times(1)).release();
    }

    /** 从未注册过断连钩子时的清理：不得抛异常，引用仍应归还。 */
    @Test
    void releaseOnConnectionClose_withoutHandler_stillReleases() {
        asyncWebRequest.startAsync();
        asyncWebRequest.releaseOnConnectionClose();
        verify(request).release();
    }
}

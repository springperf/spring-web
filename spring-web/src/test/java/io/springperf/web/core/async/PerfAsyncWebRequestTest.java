package io.springperf.web.core.async;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerfAsyncWebRequestTest {

    @Mock WebServerHttpRequest request;
    @Mock WebServerHttpResponse response;
    @Mock WebContext webContext;
    @Mock DispatcherHandler dispatcherHandler;

    PerfAsyncWebRequest asyncWebRequest;

    @BeforeEach
    void setUp() {
        asyncWebRequest = new PerfAsyncWebRequest(request, response);
    }

    @Test void startAsync_transitionsToStarted() {
        asyncWebRequest.startAsync();
        assertTrue(asyncWebRequest.isAsyncStarted());
        verify(response).setWriteRespEventListener(asyncWebRequest);
    }

    @Test void startAsync_whenAlreadyStarted_throwsISE() {
        asyncWebRequest.startAsync();
        assertThrows(IllegalStateException.class, () -> asyncWebRequest.startAsync());
    }

    @Test void startAsync_withTimeoutAndHandler_schedulesTimeout() {
        asyncWebRequest.setTimeout(1000L);
        asyncWebRequest.addTimeoutHandler(() -> {});
        asyncWebRequest.startAsync();
        verify(response).setTimeout(any(Runnable.class), eq(1000L));
    }

    @Test void startAsync_nonPositiveTimeout_doesNotScheduleTimeout() {
        asyncWebRequest.setTimeout(-1L);
        asyncWebRequest.addTimeoutHandler(() -> {});
        asyncWebRequest.startAsync();
        verify(response, never()).setTimeout(any(), anyLong());
    }

    @Test void startAsync_noTimeoutHandler_doesNotScheduleTimeout() {
        asyncWebRequest.setTimeout(1000L);
        asyncWebRequest.startAsync();
        verify(response, never()).setTimeout(any(), anyLong());
    }

    @Test void dispatch_transitionsToDispatched() {
        when(request.getWebContext()).thenReturn(webContext);
        when(webContext.getDispatcherHandler()).thenReturn(dispatcherHandler);
        asyncWebRequest.startAsync();
        asyncWebRequest.dispatch();
        assertFalse(asyncWebRequest.isAsyncStarted());
        verify(dispatcherHandler).asyncDispatch(same(request), same(response), any());
    }

    @Test void dispatch_whenNotStarted_isNoOp() {
        asyncWebRequest.dispatch();
        verify(request, never()).getWebContext();
    }

    @Test void dispatch_whenAlreadyCompleted_isNoOp() {
        asyncWebRequest.startAsync();
        asyncWebRequest.completeSuccessCallback();
        asyncWebRequest.dispatch();
        verify(request, never()).getWebContext();
    }

    @Test void completeSuccessCallback_transitionsToCompleted() {
        Runnable completionHandler = mock(Runnable.class);
        asyncWebRequest.addCompletionHandler(completionHandler);
        asyncWebRequest.completeSuccessCallback();
        assertTrue(asyncWebRequest.isAsyncComplete());
        verify(completionHandler).run();
    }

    @Test void completeSuccessCallback_withoutHandler_doesNotThrow() {
        asyncWebRequest.completeSuccessCallback();
        assertTrue(asyncWebRequest.isAsyncComplete());
    }

    @Test void completeErrorCallback_transitionsToCompleted() {
        Consumer<Throwable> errorHandler = mock(Consumer.class);
        asyncWebRequest.addErrorHandler(errorHandler);
        RuntimeException ex = new RuntimeException("test error");
        asyncWebRequest.completeErrorCallback(ex);
        assertTrue(asyncWebRequest.isAsyncComplete());
        verify(errorHandler).accept(ex);
    }

    @Test void completeErrorCallback_withoutHandler_doesNotThrow() {
        asyncWebRequest.completeErrorCallback(new RuntimeException("test"));
        assertTrue(asyncWebRequest.isAsyncComplete());
    }

    @Test void setConcurrentResultAndDispatch_dispatches() {
        when(request.getWebContext()).thenReturn(webContext);
        when(webContext.getDispatcherHandler()).thenReturn(dispatcherHandler);
        asyncWebRequest.startAsync();
        asyncWebRequest.setConcurrentResultAndDispatch("result");
        verify(dispatcherHandler).asyncDispatch(same(request), same(response), eq("result"));
        assertFalse(asyncWebRequest.isErrorHandlingInProgress());
    }

    @Test void setConcurrentResultAndDispatch_withThrowable_setsErrorHandling() {
        when(request.getWebContext()).thenReturn(webContext);
        when(webContext.getDispatcherHandler()).thenReturn(dispatcherHandler);
        asyncWebRequest.startAsync();
        RuntimeException ex = new RuntimeException("error");
        asyncWebRequest.setConcurrentResultAndDispatch(ex);
        assertTrue(asyncWebRequest.isErrorHandlingInProgress());
        verify(dispatcherHandler).asyncDispatch(same(request), same(response), same(ex));
    }

    @Test void setConcurrentResultAndDispatch_whenAlreadySet_isNoOp() {
        when(request.getWebContext()).thenReturn(webContext);
        when(webContext.getDispatcherHandler()).thenReturn(dispatcherHandler);
        asyncWebRequest.startAsync();
        asyncWebRequest.setConcurrentResultAndDispatch("first");
        asyncWebRequest.setConcurrentResultAndDispatch("second");
        verify(dispatcherHandler, times(1)).asyncDispatch(same(request), same(response), eq("first"));
    }

    @Test void setConcurrentResultAndDispatch_whenAsyncComplete_doesNotDispatch() {
        asyncWebRequest.startAsync();
        asyncWebRequest.completeSuccessCallback();
        asyncWebRequest.setConcurrentResultAndDispatch("result");
        verify(request, never()).getWebContext();
    }

    @Test void start_asyncStarted() { asyncWebRequest.start(); assertTrue(asyncWebRequest.isAsyncStarted()); }

    @Test void start_withTimeout_schedulesTimeout() {
        Runnable timeoutHandler = mock(Runnable.class);
        asyncWebRequest.addTimeoutHandler(timeoutHandler);
        asyncWebRequest.start(5000L);
        assertTrue(asyncWebRequest.isAsyncStarted());
        verify(response).setTimeout(any(Runnable.class), eq(5000L));
    }

    @Test void timeoutHandler_executesWhenTimeoutFires() {
        Runnable timeoutHandler = mock(Runnable.class);
        asyncWebRequest.addTimeoutHandler(timeoutHandler);
        asyncWebRequest.setTimeout(100L);
        asyncWebRequest.startAsync();
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(response).setTimeout(captor.capture(), eq(100L));
        captor.getValue().run();
        verify(timeoutHandler).run();
        assertTrue(asyncWebRequest.isAsyncComplete());
    }

    @Test void timeoutHandler_whenAlreadyCompleted_doesNotRunHandler() {
        Runnable timeoutHandler = mock(Runnable.class);
        asyncWebRequest.addTimeoutHandler(timeoutHandler);
        asyncWebRequest.setTimeout(100L);
        asyncWebRequest.startAsync();
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(response).setTimeout(captor.capture(), eq(100L));
        asyncWebRequest.completeSuccessCallback();
        captor.getValue().run();
        verify(timeoutHandler, never()).run();
    }

    @Test void writeStreamSuccessCallback_runsHandlerWithNull() {
        Consumer<Throwable> callback = mock(Consumer.class);
        asyncWebRequest.addWriteCallbackHandler(callback);
        asyncWebRequest.writeStreamSuccessCallback();
        verify(callback).accept(null);
    }

    @Test void writeStreamErrorCallback_runsHandlerWithThrowable() {
        Consumer<Throwable> callback = mock(Consumer.class);
        asyncWebRequest.addWriteCallbackHandler(callback);
        asyncWebRequest.writeStreamErrorCallback(new RuntimeException("write error"));
        verify(callback).accept(any(RuntimeException.class));
    }

    @Test void writeStreamErrorCallback_nullThrowable_usesDefault() {
        Consumer<Throwable> callback = mock(Consumer.class);
        asyncWebRequest.addWriteCallbackHandler(callback);
        asyncWebRequest.writeStreamErrorCallback(null);
        verify(callback).accept(any(PerfAsyncWebRequest.DefaultWriteErrorException.class));
    }

    @Test void writeStreamErrorCallback_withoutHandler_doesNotThrow() { asyncWebRequest.writeStreamErrorCallback(new RuntimeException()); }
    @Test void isAsyncStarted_newState_returnsFalse() { assertFalse(asyncWebRequest.isAsyncStarted()); }

    @Test void isAsyncStarted_afterDispatch_returnsFalse() {
        when(request.getWebContext()).thenReturn(webContext);
        when(webContext.getDispatcherHandler()).thenReturn(dispatcherHandler);
        asyncWebRequest.startAsync();
        asyncWebRequest.dispatch();
        assertFalse(asyncWebRequest.isAsyncStarted());
    }

    @Test void isAsyncComplete_afterComplete_returnsTrue() { asyncWebRequest.completeSuccessCallback(); assertTrue(asyncWebRequest.isAsyncComplete()); }
    @Test void isStarted_newState_returnsFalse() { assertFalse(asyncWebRequest.isStarted()); }
    @Test void isStarted_afterComplete_returnsTrue() { asyncWebRequest.completeSuccessCallback(); assertTrue(asyncWebRequest.isStarted()); }
    @Test void isCompleted_newState_returnsFalse() { assertFalse(asyncWebRequest.isCompleted()); }
    @Test void isCompleted_afterComplete_returnsTrue() { asyncWebRequest.completeSuccessCallback(); assertTrue(asyncWebRequest.isCompleted()); }

    @Test void complete_callsCompleteSuccessCallback() {
        Runnable completionHandler = mock(Runnable.class);
        asyncWebRequest.addCompletionHandler(completionHandler);
        asyncWebRequest.complete();
        assertTrue(asyncWebRequest.isAsyncComplete());
        verify(completionHandler).run();
    }

    @Test void defaultWriteErrorException_doesNotFillInStackTrace() {
        PerfAsyncWebRequest.DefaultWriteErrorException ex = new PerfAsyncWebRequest.DefaultWriteErrorException();
        assertSame(ex, ex.fillInStackTrace());
    }
}
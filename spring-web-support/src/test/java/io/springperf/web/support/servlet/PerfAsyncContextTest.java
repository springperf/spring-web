package io.springperf.web.support.servlet;

import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerfAsyncContextTest {

    @Mock PerfAsyncWebRequest asyncWebRequest;
    @Mock WebServerHttpRequest webRequest;
    @Mock WebServerHttpResponse webResponse;
    @Mock jakarta.servlet.ServletRequest servletRequest;
    @Mock jakarta.servlet.ServletResponse servletResponse;
    @Mock RequestContext requestContext;

    private PerfAsyncContext asyncContext;

    @BeforeEach
    void setUp() {
        lenient().when(webRequest.getRequestContext()).thenReturn(requestContext);
        asyncContext = new PerfAsyncContext(asyncWebRequest, webRequest, webResponse,
                servletRequest, servletResponse);
    }

    @Test
    void getRequest_returnsServletRequest() {
        assertSame(servletRequest, asyncContext.getRequest());
    }

    @Test
    void getResponse_returnsServletResponse() {
        assertSame(servletResponse, asyncContext.getResponse());
    }

    @Test
    void hasOriginalRequestAndResponse_returnsTrue() {
        assertTrue(asyncContext.hasOriginalRequestAndResponse());
    }

    @Test
    void dispatch_delegatesToAsyncWebRequest() {
        asyncContext.dispatch();
        verify(asyncWebRequest).dispatch();
    }

    @Test
    void complete_delegatesToAsyncWebRequest() {
        asyncContext.complete();
        verify(asyncWebRequest).complete();
    }

    @Test
    void setTimeout_setsTimeout() {
        asyncContext.setTimeout(5000L);
        assertEquals(5000L, asyncContext.getTimeout());
        verify(asyncWebRequest).setTimeout(5000L);
    }

    @Test
    void addListener_firesOnComplete() throws Exception {
        AsyncListener listener = mock(AsyncListener.class);
        asyncContext.addListener(listener);
        asyncContext.complete();
        verify(listener).onComplete(any(AsyncEvent.class));
    }

    @Test
    void constructor_doesNotRegisterHandlers() {
        verify(asyncWebRequest, never()).addTimeoutHandler(any());
        verify(asyncWebRequest, never()).addErrorHandler(any());
    }

    @Test
    void addListener_registersHandlersLazily() throws Exception {
        AsyncListener listener = mock(AsyncListener.class);
        asyncContext.addListener(listener);
        verify(asyncWebRequest).addTimeoutHandler(any(Runnable.class));
        verify(asyncWebRequest).addErrorHandler(any());
    }

    @Test
    void timeoutHandler_firesOnTimeoutOnListeners() throws Exception {
        AsyncListener listener = mock(AsyncListener.class);
        asyncContext.addListener(listener);
        org.mockito.ArgumentCaptor<Runnable> captor = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(asyncWebRequest).addTimeoutHandler(captor.capture());
        captor.getValue().run();
        verify(listener).onTimeout(any(AsyncEvent.class));
    }

    @Test
    void errorHandler_firesOnErrorOnListeners() throws Exception {
        AsyncListener listener = mock(AsyncListener.class);
        asyncContext.addListener(listener);
        org.mockito.ArgumentCaptor<java.util.function.Consumer> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.function.Consumer.class);
        verify(asyncWebRequest).addErrorHandler(captor.capture());
        captor.getValue().accept(new RuntimeException("test"));
        verify(listener).onError(any(AsyncEvent.class));
    }

    @Test
    void createListener_createsInstance() {
        AsyncListener listener = asyncContext.createListener(TestAsyncListener.class);
        assertNotNull(listener);
        assertInstanceOf(TestAsyncListener.class, listener);
    }

    @Test
    void start_executesTask() throws Exception {
        Runnable task = mock(Runnable.class);
        asyncContext.start(task);
        // timeout verify 已覆盖异步执行轮询，无需额外 Thread.sleep
        verify(task, timeout(1000)).run();
    }

    public static class TestAsyncListener implements AsyncListener {
        @Override public void onStartAsync(AsyncEvent event) { }
        @Override public void onComplete(AsyncEvent event) { }
        @Override public void onTimeout(AsyncEvent event) { }
        @Override public void onError(AsyncEvent event) { }
    }
}
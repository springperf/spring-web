package io.springperf.web.support.servlet;

import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
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
    @Mock javax.servlet.ServletRequest servletRequest;
    @Mock javax.servlet.ServletResponse servletResponse;
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
        // timeout verify 宸茶鐩栧紓姝ユ墽琛岃疆璇紝鏃犻渶棰濆 Thread.sleep
        verify(task, timeout(1000)).run();
    }

    public static class TestAsyncListener implements AsyncListener {
        @Override public void onStartAsync(AsyncEvent event) { }
        @Override public void onComplete(AsyncEvent event) { }
        @Override public void onTimeout(AsyncEvent event) { }
        @Override public void onError(AsyncEvent event) { }
    }

    /* ==================== 琛ュ厖瑕嗙洊 ==================== */

    @Test
    void dispatch_withPath_supportDispatcher_forwards() {
        io.springperf.web.support.SupportDispatcherHandler supportHandler =
                mock(io.springperf.web.support.SupportDispatcherHandler.class);
        io.springperf.web.context.WebContext webContext = mock(io.springperf.web.context.WebContext.class);
        when(webRequest.getWebContext()).thenReturn(webContext);
        when(webContext.getDispatcherHandler()).thenReturn(supportHandler);

        asyncContext.dispatch("/forward-target");

        verify(supportHandler).forward(webRequest, webResponse, "/forward-target");
    }

    @Test
    void dispatch_withPath_plainDispatcher_fallsBackToAsyncDispatch() {
        io.springperf.web.core.DispatcherHandler plain = mock(io.springperf.web.core.DispatcherHandler.class);
        io.springperf.web.context.WebContext webContext = mock(io.springperf.web.context.WebContext.class);
        when(webRequest.getWebContext()).thenReturn(webContext);
        when(webContext.getDispatcherHandler()).thenReturn(plain);

        asyncContext.dispatch("/somewhere");

        verify(asyncWebRequest).dispatch();
        verify(asyncWebRequest, never()).complete();
    }

    @Test
    void dispatch_servletContextPath_delegatesToPathDispatch() {
        io.springperf.web.context.WebContext webContext = mock(io.springperf.web.context.WebContext.class);
        when(webRequest.getWebContext()).thenReturn(webContext);
        when(webContext.getDispatcherHandler()).thenReturn(mock(io.springperf.web.core.DispatcherHandler.class));

        asyncContext.dispatch(mock(javax.servlet.ServletContext.class), "/x");

        verify(asyncWebRequest).dispatch();
    }

    @Test
    void addListener_overload_registersHandlers() {
        AsyncListener listener = mock(AsyncListener.class);
        asyncContext.addListener(listener, servletRequest, servletResponse);
        verify(asyncWebRequest).addTimeoutHandler(any(Runnable.class));
        verify(asyncWebRequest).addErrorHandler(any());
    }

    static class NoDefaultCtorListener implements AsyncListener {
        @SuppressWarnings("unused")
        NoDefaultCtorListener(String required) {
        }

        @Override public void onStartAsync(AsyncEvent event) { }
        @Override public void onComplete(AsyncEvent event) { }
        @Override public void onTimeout(AsyncEvent event) { }
        @Override public void onError(AsyncEvent event) { }
    }

    @Test
    void createListener_noDefaultConstructor_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> asyncContext.createListener(NoDefaultCtorListener.class));
    }

    @Test
    void listener_onCompleteThrows_swallowedAndContinues() throws Exception {
        AsyncListener throwing = mock(AsyncListener.class);
        doThrow(new RuntimeException("listener boom")).when(throwing).onComplete(any(AsyncEvent.class));
        AsyncListener ok = mock(AsyncListener.class);
        asyncContext.addListener(throwing);
        asyncContext.addListener(ok);

        assertDoesNotThrow(() -> asyncContext.complete());

        verify(ok).onComplete(any(AsyncEvent.class));
    }
}

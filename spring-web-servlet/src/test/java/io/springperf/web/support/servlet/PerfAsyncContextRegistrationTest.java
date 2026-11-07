package io.springperf.web.support.servlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;

/** listener 注册时 request/response 为 null 时，AsyncEvent 必须回退到 context 持有的原始对象。 */
@ExtendWith(MockitoExtension.class)
class PerfAsyncContextRegistrationTest {

    @Mock
    PerfAsyncWebRequest asyncWebRequest;
    @Mock
    WebServerHttpRequest webRequest;
    @Mock
    WebServerHttpResponse webResponse;
    @Mock
    jakarta.servlet.ServletRequest servletRequest;
    @Mock
    jakarta.servlet.ServletResponse servletResponse;
    @Mock
    RequestContext requestContext;

    private PerfAsyncContext asyncContext;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(webRequest.getRequestContext()).thenReturn(requestContext);
        asyncContext = new PerfAsyncContext(asyncWebRequest, webRequest, webResponse, servletRequest, servletResponse,
                true);
    }

    private static AsyncEvent completeEvent(AsyncListener listener) throws IOException {
        ArgumentCaptor<AsyncEvent> captor = ArgumentCaptor.forClass(AsyncEvent.class);
        verify(listener).onComplete(captor.capture());
        return captor.getValue();
    }

    @Test
    void nullRequestAndResponseFallBackOnComplete() throws Exception {
        AsyncListener listener = mock(AsyncListener.class);
        asyncContext.addListener(listener, null, null);

        asyncContext.complete();

        AsyncEvent event = completeEvent(listener);
        assertSame(servletRequest, event.getSuppliedRequest());
        assertSame(servletResponse, event.getSuppliedResponse());
    }

    @Test
    void partialNullRegistrationKeepsExplicitRequest() throws Exception {
        AsyncListener listener = mock(AsyncListener.class);
        jakarta.servlet.ServletRequest wrapped = mock(jakarta.servlet.ServletRequest.class);
        asyncContext.addListener(listener, wrapped, null);

        asyncContext.complete();

        AsyncEvent event = completeEvent(listener);
        assertSame(wrapped, event.getSuppliedRequest());
        assertSame(servletResponse, event.getSuppliedResponse());
    }

    @Test
    void nullRequestAndResponseFallBackOnTimeout() throws Exception {
        AsyncListener listener = mock(AsyncListener.class);
        asyncContext.addListener(listener, null, null);
        ArgumentCaptor<Runnable> timeout = ArgumentCaptor.forClass(Runnable.class);
        verify(asyncWebRequest).addTimeoutHandler(timeout.capture());

        timeout.getValue().run();

        ArgumentCaptor<AsyncEvent> event = ArgumentCaptor.forClass(AsyncEvent.class);
        verify(listener).onTimeout(event.capture());
        assertSame(servletRequest, event.getValue().getSuppliedRequest());
        assertSame(servletResponse, event.getValue().getSuppliedResponse());
    }

    @Test
    void nullRequestAndResponseFallBackOnErrorWithThrowable() throws Exception {
        AsyncListener listener = mock(AsyncListener.class);
        asyncContext.addListener(listener, null, null);
        ArgumentCaptor<Consumer> errorHandler = ArgumentCaptor.forClass(Consumer.class);
        verify(asyncWebRequest).addErrorHandler(errorHandler.capture());
        RuntimeException cause = new RuntimeException("boom");

        errorHandler.getValue().accept(cause);

        ArgumentCaptor<AsyncEvent> event = ArgumentCaptor.forClass(AsyncEvent.class);
        verify(listener).onError(event.capture());
        assertSame(servletRequest, event.getValue().getSuppliedRequest());
        assertSame(servletResponse, event.getValue().getSuppliedResponse());
        assertSame(cause, event.getValue().getThrowable());
    }
}

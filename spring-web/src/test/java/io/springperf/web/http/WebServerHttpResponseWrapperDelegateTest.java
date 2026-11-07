package io.springperf.web.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;

/** 验证 wrapper 的委托语义（接口 default 方法被实现类覆写时，运行期不会落到接口实现）。 */
class WebServerHttpResponseWrapperDelegateTest {

    private final WebServerHttpResponse delegate = mock(WebServerHttpResponse.class);
    private final WebServerHttpResponseWrapper wrapper = new WebServerHttpResponseWrapper(delegate);

    @Test
    void stateGettersDelegateToWrappedResponse() {
        when(delegate.isHandled()).thenReturn(true);
        when(delegate.isCommitted()).thenReturn(true);
        when(delegate.getStatus()).thenReturn(HttpStatusCode.valueOf(418));
        when(delegate.getCharacterEncoding()).thenReturn(StandardCharsets.ISO_8859_1);
        when(delegate.getBufferSize()).thenReturn(2048);
        when(delegate.resetBuffer()).thenReturn(true);
        when(delegate.isStreaming()).thenReturn(true);
        when(delegate.markStreamCompleted()).thenReturn(true);

        assertTrue(wrapper.isHandled());
        assertTrue(wrapper.isCommitted());
        assertEquals(418, wrapper.getStatus().value());
        assertEquals(StandardCharsets.ISO_8859_1, wrapper.getCharacterEncoding());
        assertEquals(2048, wrapper.getBufferSize());
        assertTrue(wrapper.resetBuffer());
        assertTrue(wrapper.isStreaming());
        assertTrue(wrapper.markStreamCompleted());
    }

    @Test
    void writeOperationsDelegateWithExactArguments() throws IOException {
        WriteRespEventListener listener = mock(WriteRespEventListener.class);
        InputStream input = new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8));
        byte[] data = "body".getBytes(StandardCharsets.UTF_8);

        wrapper.setCharacterEncoding(StandardCharsets.UTF_8);
        wrapper.setWriteRespEventListener(listener);
        wrapper.addWriteRespEventListener(listener);
        wrapper.writeStream(input);
        wrapper.writeStream(input, 3L);
        wrapper.writeBytes(data);
        wrapper.flush(true);
        wrapper.flushChunked();
        wrapper.endStream();
        wrapper.setBeforeCommit(() -> {
        });
        wrapper.close();

        verify(delegate).setCharacterEncoding(StandardCharsets.UTF_8);
        verify(delegate).setWriteRespEventListener(listener);
        verify(delegate).addWriteRespEventListener(listener);
        verify(delegate).writeStream(input);
        verify(delegate).writeStream(input, 3L);
        verify(delegate).writeBytes(data);
        verify(delegate).flush(true);
        verify(delegate).flushChunked();
        verify(delegate).endStream();
        verify(delegate).setBeforeCommit(any(Runnable.class));
        verify(delegate).close();
    }

    @Test
    void sendErrorOverloadsDegradeToTwoArgumentDelegateCall() {
        HttpStatusCode status = HttpStatusCode.valueOf(503);
        Throwable cause = new IllegalStateException("down");

        wrapper.sendError(status, "plain");
        wrapper.sendError(status, "with cause", cause);
        wrapper.sendError(status, "all flags", cause, true, false, false);

        verify(delegate, times(3)).sendError(eq(status), anyString());
        verify(delegate).sendError(status, "plain");
        verify(delegate, never()).sendError(any(HttpStatusCode.class), anyString(), any(Throwable.class));
    }

    @Test
    void markStreamCompletedReturnsTheDelegateDecision() {
        when(delegate.markStreamCompleted()).thenReturn(false, true);

        assertFalse(wrapper.markStreamCompleted(), "首次写入权已被占用时应返回 false");
        assertTrue(wrapper.markStreamCompleted());
    }

    @Test
    void setTimeoutAndHandledDelegateWithReturnValues() {
        Runnable task = () -> {
        };
        when(delegate.setHandled()).thenReturn(true);

        wrapper.setTimeout(task, 500L);
        assertTrue(wrapper.setHandled());

        verify(delegate).setTimeout(task, 500L);
        verify(delegate).setHandled();
    }
}

package io.springperf.web.http;

import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebServerHttpResponseWrapperTest {

    private final WebServerHttpResponse delegate = mock(WebServerHttpResponse.class);
    private final WebServerHttpResponseWrapper wrapper = new WebServerHttpResponseWrapper(delegate);

    @Test void getResponse_returnsDelegate() { assertSame(delegate, wrapper.getResponse()); }
    @Test void isHandled_delegates() { when(delegate.isHandled()).thenReturn(true); assertTrue(wrapper.isHandled()); }
    @Test void isCommitted_delegates() { when(delegate.isCommitted()).thenReturn(true); assertTrue(wrapper.isCommitted()); }
    @Test void getStatus_delegates() { when(delegate.getStatus()).thenReturn(HttpStatus.NOT_FOUND); assertEquals(HttpStatus.NOT_FOUND, wrapper.getStatus()); }
    @Test void setCharacterEncoding_delegates() { wrapper.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8); verify(delegate).setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8); }
    @Test void getCharacterEncoding_delegates() { when(delegate.getCharacterEncoding()).thenReturn(java.nio.charset.StandardCharsets.ISO_8859_1); assertEquals(java.nio.charset.StandardCharsets.ISO_8859_1, wrapper.getCharacterEncoding()); }
    @Test void getBufferSize_delegates() { when(delegate.getBufferSize()).thenReturn(42); assertEquals(42, wrapper.getBufferSize()); }
    @Test void resetBuffer_delegates() { when(delegate.resetBuffer()).thenReturn(true); assertTrue(wrapper.resetBuffer()); }
    @Test void setTimeout_delegates() { Runnable task = () -> {}; ScheduledFuture future = mock(ScheduledFuture.class); when(delegate.setTimeout(task, 1000)).thenReturn(future); assertSame(future, wrapper.setTimeout(task, 1000)); }
    @Test void getWebContext_delegates() { WebContext ctx = mock(WebContext.class); when(delegate.getWebContext()).thenReturn(ctx); assertSame(ctx, wrapper.getWebContext()); }
    @Test void setWriteRespEventListener_delegates() { WriteRespEventListener listener = mock(WriteRespEventListener.class); wrapper.setWriteRespEventListener(listener); verify(delegate).setWriteRespEventListener(listener); }
    @Test void setHandled_delegates() { when(delegate.setHandled()).thenReturn(true); assertTrue(wrapper.setHandled()); }
    @Test void sendError_delegates() { wrapper.sendError(HttpStatus.BAD_REQUEST); verify(delegate).sendError(HttpStatus.BAD_REQUEST); }
    @Test void sendError_withMessage_delegates() { wrapper.sendError(HttpStatus.NOT_FOUND, "custom"); verify(delegate).sendError(HttpStatus.NOT_FOUND, "custom"); }
    @Test void writeStream_delegates() { InputStream s = mock(InputStream.class); wrapper.writeStream(s); verify(delegate).writeStream(s); }
    @Test void writeFile_delegates() { File f = new File("/tmp/test"); wrapper.writeFile(f); verify(delegate).writeFile(f); }
    @Test void setStatusCode_delegates() { wrapper.setStatusCode(HttpStatus.CREATED); verify(delegate).setStatusCode(HttpStatus.CREATED); }
    @Test void flush_delegates() throws Exception { wrapper.flush(); verify(delegate).flush(); }
    @Test void close_delegates() { wrapper.close(); verify(delegate).close(); }
    @Test void getBody_delegates() throws Exception { java.io.OutputStream s = mock(java.io.OutputStream.class); when(delegate.getBody()).thenReturn(s); assertSame(s, wrapper.getBody()); }
    @Test void getHeaders_delegates() { org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders(); when(delegate.getHeaders()).thenReturn(h); assertSame(h, wrapper.getHeaders()); }
}
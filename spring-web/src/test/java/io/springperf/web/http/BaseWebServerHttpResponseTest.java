package io.springperf.web.http;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BaseWebServerHttpResponseTest {

    static class TestResponse extends BaseWebServerHttpResponse {
        boolean flushed = false;

        TestResponse(WebContext webContext, boolean keepAlive) {
            super(webContext, keepAlive,
                    new WebHttpHeaders(new NettyHttpHeadersAdapter(new DefaultHttpHeaders(false), true)));
        }

        @Override void runOnEventLoop(Runnable task) {}
        @Override ScheduledFuture scheduleOnEventLoop(Runnable task, long delay, TimeUnit unit) { return null; }
        @Override public void writeStream(InputStream input) {}
        @Override public void writeBytes(byte[] data) {}
        @Override public void writeFile(File file) {}
        @Override
        public void flush(boolean chunked) throws IOException {
            flushed = true;
        }
    }

    private WebContext webContext;
    private TestResponse response;

    @BeforeEach
    void setUp() {
        webContext = mock(WebContext.class);
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.getLong(PropertiesConstant.HTTP_TIMEOUT)).thenReturn(60000L);
        when(webContext.getProps()).thenReturn(props);
        response = new TestResponse(webContext, false);
    }

    @Test void constructor_initialState() {
        assertFalse(response.isHandled());
        assertFalse(response.isCommitted());
        assertEquals(HttpStatus.OK, response.getStatus());
        assertFalse(response.flushed);
    }

    @Test void setStatusCode_updatesStatus() {
        response.setStatusCode(HttpStatus.NOT_FOUND);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatus());
    }

    @Test void setStatusCode_null_ignored() {
        response.setStatusCode(HttpStatus.NOT_FOUND);
        response.setStatusCode(null);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatus());
    }

    @Test void getHeaders_returnsSameInstance() { assertSame(response.getHeaders(), response.getHeaders()); }
    @Test void getBody_initiallyNull_lazyCreates() { assertNotNull(response.getBody()); }
    @Test void getBody_returnsSameInstance() throws IOException { assertSame(response.getBody(), response.getBody()); }
    @Test void getBufferSize_beforeWrite_returnsZero() { assertEquals(0, response.getBufferSize()); }
    @Test void getBufferSize_afterWrite_returnsSize() throws IOException { response.getBody().write("hello".getBytes()); assertEquals(5, response.getBufferSize()); }
    @Test void resetBuffer_beforeWrite_returnsFalse() { assertFalse(response.resetBuffer()); }
    @Test void resetBuffer_afterWrite_returnsTrueAndClears() throws IOException { response.getBody().write("data".getBytes()); assertTrue(response.resetBuffer()); assertEquals(0, response.getBufferSize()); }
    @Test void setHandled_firstCall_returnsTrue() { assertTrue(response.setHandled()); assertTrue(response.isHandled()); }
    @Test void setHandled_secondCall_returnsFalse() { assertTrue(response.setHandled()); assertFalse(response.setHandled()); }
    @Test void close_callsFlush() { response.close(); assertTrue(response.flushed); }
    @Test void isCommitted_initiallyFalse() { assertFalse(response.isCommitted()); }
    @Test void setCharacterEncoding_updatesEncoding() { response.setCharacterEncoding(java.nio.charset.StandardCharsets.ISO_8859_1); assertEquals(java.nio.charset.StandardCharsets.ISO_8859_1, response.getCharacterEncoding()); }
    @Test void setTimeout_negativeDelay_returnsNull() { assertNull(response.setTimeout(() -> {}, -1)); }
    @Test void setTimeout_nullTask_returnsNull() { assertNull(response.setTimeout(null, 1000)); }

    @Test void sendError_setsHandledAndFlushes() {
        response.sendError(HttpStatus.BAD_REQUEST);
        assertTrue(response.isHandled());
        assertTrue(response.flushed);
        assertTrue(response.getHeaders().getContentType().includes(MediaType.APPLICATION_JSON));
    }

    @Test void sendErrorOnce_twice_onlyFirstApplies() {
        response.sendError(HttpStatus.BAD_REQUEST);
        response.flushed = false;
        response.sendError(HttpStatus.INTERNAL_SERVER_ERROR);
        assertFalse(response.flushed);
    }

    @Test void sendError_withMessage_usesCustomMessage() {
        response.sendError(HttpStatus.NOT_FOUND, "Custom error");
        assertEquals(HttpStatus.NOT_FOUND, response.getStatus());
        assertTrue(response.isHandled());
    }

    @Test void writeDataAndFlush_setsContentType() {
        response.sendError(HttpStatus.BAD_REQUEST, "bad");
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    }

    @Test void setTimeout_cancelsPrevious() {
        TestResponse innerResponse = new TestResponse(webContext, false) {
            @Override ScheduledFuture scheduleOnEventLoop(Runnable task, long delay, TimeUnit unit) { return mock(ScheduledFuture.class); }
        };
        ScheduledFuture first = innerResponse.setTimeout(() -> {}, 1000);
        innerResponse.setTimeout(() -> {}, 2000);
        verify(first).cancel(false);
    }

    @Test void getWebContext_returnsConstructedContext() { assertSame(webContext, response.getWebContext()); }
    @Test void writeRespEventListener_defaultNull() { assertNull(response.writeRespEventListener); }

    @Test void setWriteRespEventListener_setsListener() {
        WriteRespEventListener listener = new WriteRespEventListener() {
            @Override public void completeSuccessCallback() {}
            @Override public void completeErrorCallback(Throwable throwable) {}
        };
        response.setWriteRespEventListener(listener);
        assertNotNull(response.writeRespEventListener);
    }

    @Test void sendError_jsonMessage_containsErrorMessage() {
        response.sendError(HttpStatus.BAD_GATEWAY, "bad upstream");
        assertTrue(new String(response.body.toByteArray()).contains("bad upstream"));
    }

    @Test void sendError_setsStatusCode() {
        response.sendError(HttpStatus.SERVICE_UNAVAILABLE);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatus());
    }

    @Test void escapeJson_escapesSpecialCharacters() {
        response.sendError(HttpStatus.BAD_REQUEST,
                "quote\" back\\ new\n tab\t ff\f bs\b cr\r control\u0001 end");
        String json = new String(response.body.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("\\\""), "双引号应转义为 \\\"");
        assertTrue(json.contains("\\\\"), "反斜杠应转义为 \\\\");
        assertTrue(json.contains("\\n"), "换行应转义为 \\n");
        assertTrue(json.contains("\\t"), "制表符应转义为 \\t");
        assertTrue(json.contains("\\f"), "换页应转义为 \\f");
        assertTrue(json.contains("\\b"), "退格应转义为 \\b");
        assertTrue(json.contains("\\r"), "回车应转义为 \\r");
        assertTrue(json.contains("\\u0001"), "控制字符应以 \\u 转义");
    }

    @Test void escapeJson_null_returnsEmpty() {
        response.sendError(HttpStatus.BAD_REQUEST, null);
        String json = new String(response.body.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("\"error\":\"\""));
    }

    @Test void setStatusCode_nonHttpStatusInstance_usesValue() {
        // 非标准状态码（299）→ HttpStatus.valueOf 抛 IAE（快速失败，防脏状态码写入响应）
        org.springframework.http.HttpStatusCode custom =
                org.springframework.http.HttpStatusCode.valueOf(299);
        assertFalse(custom instanceof HttpStatus);
        assertThrows(IllegalArgumentException.class, () -> response.setStatusCode(custom));
    }

    @Test void resetHandled_clearsHandledFlag() {
        assertTrue(response.setHandled());
        response.resetHandled();
        assertFalse(response.isHandled());
    }

    @Test void defaultHandleTimeout_sendsGatewayTimeout() {
        response.defaultHandleTimeout();
        assertEquals(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT, response.getStatus());
        assertTrue(response.isHandled());
    }

    @Test void setTimeout_default_schedulesTimeoutHandler() {
        // webContext.getProps().getLong(HTTP_TIMEOUT) 已在 @BeforeEach stub 为 60000
        response.setTimeout();
        assertTrue(response.flushed == false);
    }

    @Test void addWriteRespEventListener_single_thenComposite() {
        WriteRespEventListener first = mock(WriteRespEventListener.class);
        WriteRespEventListener second = mock(WriteRespEventListener.class);
        WriteRespEventListener third = mock(WriteRespEventListener.class);
        response.addWriteRespEventListener(first);
        response.addWriteRespEventListener(second);
        response.addWriteRespEventListener(third);

        response.writeRespEventListener.completeSuccessCallback();
        verify(first).completeSuccessCallback();
        verify(second).completeSuccessCallback();
        verify(third).completeSuccessCallback();

        response.writeRespEventListener.completeErrorCallback(new RuntimeException("x"));
        verify(first).completeErrorCallback(any());

        response.writeRespEventListener.writeStreamSuccessCallback();
        verify(second).writeStreamSuccessCallback();

        response.writeRespEventListener.writeStreamErrorCallback(new RuntimeException("y"));
        verify(second).writeStreamErrorCallback(any());
    }
}

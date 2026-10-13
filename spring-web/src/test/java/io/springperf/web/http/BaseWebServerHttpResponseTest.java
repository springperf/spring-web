package io.springperf.web.http;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.server.ErrorResponseConfig;
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
            // 与 NettyServerHttpResponse#flush 一致：先执行提交前回调（把 Writer 编码缓冲刷入响应体）再提交，
            // 否则「错误响应不得夹带业务内容」这类顺序断言在测试替身上失焦。
            runBeforeCommitOnce();
            flushed = true;
        }
    }

    private WebContext webContext;
    private TestResponse response;

    @BeforeEach
    void setUp() {
        webContext = mock(WebContext.class);
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.getHttpTimeoutMillis()).thenReturn(60000L);
        when(webContext.getProps()).thenReturn(props);
        response = new TestResponse(webContext, false);
    }

    /** 关闭 whitelabel + include-message=always 的错误响应配置（JSON 错误体）。 */
    private static ErrorResponseConfig jsonConfig() {
        return new ErrorResponseConfig(ErrorResponseConfig.IncludePolicy.NEVER,
                ErrorResponseConfig.IncludePolicy.ALWAYS,
                ErrorResponseConfig.IncludePolicy.NEVER, false, "/error");
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
        // 默认 whitelabel 启用 → HTML 错误页（Accept 未限制时）
        assertTrue(response.getHeaders().getContentType().includes(MediaType.TEXT_HTML));
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

    /**
     * 3 参 default 重载（{@code sendError(status, message, cause)}）：与 master 同款——退化为 2 参重载后
     * 仍走「完整入口」渲染（message 按 include-message 策略暴露，cause 仅作为渲染上下文传入）。
     */
    @Test void sendError_withCause_delegatesToMessageOverload() {
        when(webContext.getWebComponent(ErrorResponseConfig.class)).thenReturn(jsonConfig());
        response.sendError(HttpStatus.BAD_GATEWAY, "bad upstream", new IllegalStateException("root"));
        assertTrue(response.isHandled());
        assertTrue(response.flushed);
        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatus());
        assertTrue(new String(response.body.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).contains("bad upstream"));
    }

    /**
     * 5 参 default 重载：{@code on-param} 模式下 trace 命中标志必须透传到完整入口，
     * 否则 {@code server.error.include-stacktrace=on-param} 形同虚设。
     */
    @Test void sendError_fiveArg_forwardsOnParamFlags() {
        when(webContext.getWebComponent(ErrorResponseConfig.class)).thenReturn(new ErrorResponseConfig(
                ErrorResponseConfig.IncludePolicy.ON_PARAM, ErrorResponseConfig.IncludePolicy.NEVER,
                ErrorResponseConfig.IncludePolicy.NEVER, false, "/error"));

        response.sendError(HttpStatus.BAD_GATEWAY, "bad upstream", new IllegalStateException("root"), true, false);
        String traceHit = new String(response.body.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(traceHit.contains("IllegalStateException"), "trace 命中时异常栈应暴露，实际 body=" + traceHit);

        TestResponse miss = new TestResponse(webContext, false);
        miss.sendError(HttpStatus.BAD_GATEWAY, "bad upstream", new IllegalStateException("root"), false, false);
        String traceMiss = new String(miss.body.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(traceMiss.contains("IllegalStateException"), "trace 未命中时不得暴露异常栈，实际 body=" + traceMiss);
    }

    /**
     * 6 参 default 重载（接口层，master 同款）：未覆写完整入口的实现类只需实现 2 参重载即可编译运行——
     * 6 参默认实现按 6 参 → 3 参 → 2 参链路退化（fail-closed：不透出栈/绑定错误）。
     */
    @Test void sendError_defaultOverloadChain_degradesToMinimalContract() {
        WebServerHttpResponse minimal = mock(WebServerHttpResponse.class, CALLS_REAL_METHODS);
        minimal.sendError(HttpStatus.BAD_REQUEST, "m", null, true, true, true);
        verify(minimal).sendError(HttpStatus.BAD_REQUEST, "m", null);
        verify(minimal).sendError(HttpStatus.BAD_REQUEST, "m");

        WebServerHttpResponse fiveArg = mock(WebServerHttpResponse.class, CALLS_REAL_METHODS);
        fiveArg.sendError(HttpStatus.BAD_REQUEST, "m", null, true, true);
        verify(fiveArg).sendError(HttpStatus.BAD_REQUEST, "m", null, true, true, false);
        verify(fiveArg).sendError(HttpStatus.BAD_REQUEST, "m");
    }

    @Test void writeDataAndFlush_setsContentType() {
        // 关闭 whitelabel → JSON 错误体（application/json;charset=UTF-8）
        when(webContext.getWebComponent(ErrorResponseConfig.class)).thenReturn(jsonConfig());
        response.sendError(HttpStatus.BAD_REQUEST, "bad");
        assertTrue(response.getHeaders().getContentType().includes(MediaType.APPLICATION_JSON));
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
        // include-message=always 才暴露 message；默认 never 下 HTML 页面不含业务文案
        when(webContext.getWebComponent(ErrorResponseConfig.class)).thenReturn(jsonConfig());
        response.sendError(HttpStatus.BAD_GATEWAY, "bad upstream");
        assertTrue(new String(response.body.toByteArray()).contains("bad upstream"));
    }

    @Test void sendError_setsStatusCode() {
        response.sendError(HttpStatus.SERVICE_UNAVAILABLE);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatus());
    }

    /**
     * include-message 默认 never：whitelabel 页面不含业务 message。
     *
     * <p>未注册 {@code ErrorResponseConfig} 时按默认（whitelabel 开启、三项 never）渲染——即错误响应默认不泄漏业务文案。</p>
     */
    @Test void sendError_defaultPolicy_hidesMessage() {
        response.sendError(HttpStatus.BAD_GATEWAY, "bad upstream");
        String body = new String(response.body.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(body.contains("bad upstream"));
        assertTrue(body.contains("502"));
    }

    /** 默认 never 策略下，即便 message 含引号/反斜杠/控制字符，原文与特殊字符都不得出现在响应体中。 */
    @Test void sendError_messageHiddenByDefault_evenWithSpecialChars() {
        response.sendError(HttpStatus.BAD_REQUEST, "quote\" back\\ new\n tab\t control\u0001 end");
        String body = new String(response.body.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        // 默认 never：原文不出现，避免特殊字符破坏输出
        assertFalse(body.contains("quote"));
        assertFalse(body.contains("\u0001"));
    }

    /*
     * 版本层差异（对应 master 的 setStatusCode_nonHttpStatusInstance_usesValue /
     * sendError_nonStandardCode_writesRawValue）：Boot 2.7 / Spring 5.3 无 {@code HttpStatusCode} 接口，
     * {@code BaseWebServerHttpResponse.setStatusCode} 只接受 {@link HttpStatus} 枚举，非标准码（299/599）
     * 无法表达（{@code HttpStatus.valueOf} 抛 IllegalArgumentException）→ 无可适配点，不移植。
     * 同类裁定见 {@code PerfHttpServletResponseTest}（该处以其分支实际边界 assertThrows 固化）。
     */

    /**
     * 错误响应不得夹带业务先前经 servlet Writer 写入的内容：{@code beforeCommit}（把 Writer 编码缓冲刷入响应体）
     * 必须先于 {@code resetBuffer()} 执行，否则「写入后未 flush 就出错」的内容会被追加到错误体之后，形成畸形响应体。
     * <p>
     * 对齐 Tomcat / master：{@code writeDataAndFlush} 首行即 {@code runBeforeCommitOnce()}。
     * </p>
     */
    @Test void sendError_discardsPendingBeforeCommitContent() {
        when(webContext.getWebComponent(ErrorResponseConfig.class)).thenReturn(jsonConfig());
        response.setBeforeCommit(() -> {
            try {
                response.getBody().write("stray-business-content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });

        response.sendError(HttpStatus.BAD_REQUEST, "bad");

        String body = new String(response.body.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(body.contains("stray-business-content"),
                "错误响应不得夹带 beforeCommit 刷出的业务内容，实际 body=" + body);
    }

    @Test void sendError_jsonMessage_escapesSpecialCharacters() {
        when(webContext.getWebComponent(ErrorResponseConfig.class)).thenReturn(jsonConfig());
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

    @Test void sendError_nullMessage_omitsMessageField() {
        when(webContext.getWebComponent(ErrorResponseConfig.class)).thenReturn(jsonConfig());
        response.sendError(HttpStatus.BAD_REQUEST, null);
        String json = new String(response.body.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains("\"status\":400"));
        // message 为 null 时不输出该字段（而非输出 null 字面量）
        assertFalse(json.contains("\"message\""));
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
        // webContext.getProps().getHttpTimeoutMillis() 已在 @BeforeEach stub 为 60000
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

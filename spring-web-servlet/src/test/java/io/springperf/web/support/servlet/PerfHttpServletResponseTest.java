package io.springperf.web.support.servlet;

import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.context.PerfServletContext;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerfHttpServletResponseTest {

    @Mock
    WebServerHttpResponse response;
    @Mock
    HttpHeaders headers;

    private PerfHttpServletResponse servletResponse;

    @BeforeEach
    void setUp() {
        lenient().when(response.getHeaders()).thenReturn(headers);
        servletResponse = new PerfHttpServletResponse(response);
    }

    @Test
    void setStatus_delegatesStatusCode() {
        servletResponse.setStatus(404);
        verify(response).setStatusCode(HttpStatus.valueOf(404));
    }

    @Test
    void setStatus_nonStandardCode_writesRawValue() {
        servletResponse.setStatus(599);
        verify(response).setStatusCode(
                argThat((org.springframework.http.HttpStatusCode sc) -> sc != null && sc.value() == 599));
    }

    @Test
    void sendError_nonStandardCode_delegatesRawValue() {
        servletResponse.sendError(599, "upstream");
        verify(response).sendError(
                argThat((org.springframework.http.HttpStatusCode sc) -> sc != null && sc.value() == 599),
                eq("upstream"), isNull(), eq(false), eq(false), eq(false));
    }

    @Test
    void sendError_nonStandardCode_noMessage_delegatesRawValue() {
        servletResponse.sendError(599);
        verify(response).sendError(
                argThat((org.springframework.http.HttpStatusCode sc) -> sc != null && sc.value() == 599), isNull(),
                isNull(), eq(false), eq(false), eq(false));
    }

    @Test
    void getStatus_returnsStatusValue() {
        when(response.getStatus()).thenReturn(HttpStatus.CREATED);
        assertEquals(201, servletResponse.getStatus());
    }

    @Test
    void setHeader_delegatesToHeadersSet() {
        servletResponse.setHeader("X-Custom", "value");
        verify(headers).set("X-Custom", "value");
    }

    @Test
    void addHeader_delegatesToHeadersAdd() {
        servletResponse.addHeader("X-Custom", "value");
        verify(headers).add("X-Custom", "value");
    }

    @Test
    void getHeader_returnsFirstValue() {
        when(headers.getFirst("X-Custom")).thenReturn("value");
        assertEquals("value", servletResponse.getHeader("X-Custom"));
    }

    @Test
    void getHeaders_returnsCollection() {
        java.util.Collection<String> values = java.util.Arrays.asList("a", "b");
        when(headers.get("X-Custom")).thenReturn((java.util.List<String>) (java.util.List) values);
        assertTrue(servletResponse.getHeaders("X-Custom").containsAll(values));
    }

    @Test
    void getHeaderNames_returnsSet() {
        java.util.Set<String> names = new java.util.HashSet<>(java.util.Arrays.asList("Content-Type", "X-Custom"));
        when(headers.keySet()).thenReturn(names);
        assertEquals(names, servletResponse.getHeaderNames());
    }

    @Test
    void setContentType_delegatesToSetHeader() {
        servletResponse.setContentType("application/json");
        verify(headers).set("Content-Type", "application/json");
    }

    @Test
    void getContentType_returnsHeaderValue() {
        when(headers.getFirst("Content-Type")).thenReturn("text/html");
        assertEquals("text/html", servletResponse.getContentType());
    }

    @Test
    void getCharacterEncoding_returnsFromResponse() {
        when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        assertEquals("UTF-8", servletResponse.getCharacterEncoding());
    }

    @Test
    void getCharacterEncoding_null_returnsNull() {
        when(response.getCharacterEncoding()).thenReturn(null);
        assertNull(servletResponse.getCharacterEncoding());
    }

    @Test
    void setCharacterEncoding_delegatesToResponse() {
        servletResponse.setCharacterEncoding("ISO-8859-1");
        verify(response).setCharacterEncoding(StandardCharsets.ISO_8859_1);
    }

    @Test
    void getOutputStream_writesToResponseBody() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(response.getBody()).thenReturn(baos);
        ServletOutputStream out = servletResponse.getOutputStream();
        out.write(65);
        out.write("hello".getBytes());
        assertArrayEquals(new byte[] { 65, 'h', 'e', 'l', 'l', 'o' }, baos.toByteArray());
    }

    @Test
    void getOutputStream_print_writesEncodedString() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(response.getBody()).thenReturn(baos);
        when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        servletResponse.getOutputStream().print("hello");
        assertEquals("hello", baos.toString("UTF-8"));
    }

    // Servlet 规范/Tomcat：getOutputStream().flush() 提交响应并写出已缓冲内容（渐进式输出）
    @Test
    void getOutputStream_flush_commitsChunked() throws Exception {
        servletResponse.getOutputStream().flush();
        verify(response).flushChunked();
    }

    @Test
    void getOutputStream_setWriteListener_throwsUnsupported() throws Exception {
        assertThrows(UnsupportedOperationException.class,
                () -> servletResponse.getOutputStream().setWriteListener(null));
    }

    @Test
    void getOutputStream_isReady_returnsTrue() throws Exception {
        assertTrue(servletResponse.getOutputStream().isReady());
    }

    // ========== 2-32：getOutputStream 应返回同一实例（Servlet 规范） ==========
    @Test
    void getOutputStream_returnsSameInstance() throws Exception {
        jakarta.servlet.ServletOutputStream out1 = servletResponse.getOutputStream();
        jakarta.servlet.ServletOutputStream out2 = servletResponse.getOutputStream();
        assertSame(out1, out2, "多次 getOutputStream 必须返回同一实例");
    }

    @Test
    void getOutputStream_afterRebind_returnsNewInstance() throws Exception {
        jakarta.servlet.ServletOutputStream out1 = servletResponse.getOutputStream();
        WebServerHttpResponse newResponse = mock(WebServerHttpResponse.class);
        servletResponse.rebind(newResponse);
        jakarta.servlet.ServletOutputStream out2 = servletResponse.getOutputStream();
        assertNotSame(out1, out2, "rebind 后底层响应已替换，应返回新的输出流实例");
    }

    @Test
    void getWriter_writesToResponseBody() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(response.getBody()).thenReturn(baos);
        when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        PrintWriter writer = servletResponse.getWriter();
        writer.print("test content");
        writer.flush();
        assertEquals("test content", baos.toString("UTF-8"));
    }

    // Tomcat 语义（autoFlush=false）：println 不提交响应——内容留在 Writer 编码缓冲，
    // 但必须标记「业务已写响应体」，否则框架收尾不提交，响应永不发出（客户端挂到超时）。
    @Test
    void getWriter_println_doesNotCommitButMarksHandled() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        lenient().when(response.getBody()).thenReturn(baos);
        lenient().when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        servletResponse.getWriter().println("autoflush-off");
        verify(response, never()).flushChunked();
        verify(response).setHandled();
        assertEquals("", baos.toString("UTF-8"), "println 不得提交/写出：内容仍在 Writer 编码缓冲中");
    }

    // 显式 flush() 才提交：编码缓冲先落响应体，再以 chunked 提交（渐进式输出）
    @Test
    void getWriter_explicitFlush_flushesEncoderThenCommitsChunked() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        lenient().when(response.getBody()).thenReturn(baos);
        lenient().when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        PrintWriter writer = servletResponse.getWriter();
        writer.write("segment");
        writer.flush();
        assertEquals("segment", baos.toString("UTF-8"), "显式 flush 应先把编码缓冲写入响应体");
        verify(response).flushChunked();
    }

    // 空 flush 不应凭空发出 chunked 提交（本框架约定：无正文时由请求收尾决定是否提交）
    @Test
    void getWriter_flushWithoutContent_doesNotCommit() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        lenient().when(response.getBody()).thenReturn(baos);
        lenient().when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        servletResponse.getWriter().flush();
        verify(response, never()).flushChunked();
    }

    // write(int)/write(char[]) 是 print(char) 与 write(char[]) 的实际落点：
    // 漏标 handled 会让「写了内容但未 flush」的响应永远发不出去
    @Test
    void getWriter_printChar_marksBodyWritten() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        lenient().when(response.getBody()).thenReturn(baos);
        lenient().when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        servletResponse.getWriter().print('x');
        verify(response).setHandled();
    }

    @Test
    void getWriter_writeCharArray_marksBodyWritten() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        lenient().when(response.getBody()).thenReturn(baos);
        lenient().when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        servletResponse.getWriter().write(new char[] { 'a', 'b' });
        verify(response).setHandled();
    }

    @Test
    void flushBuffer_commitsChunkedStream() throws Exception {
        servletResponse.flushBuffer();
        verify(response).flushChunked();
    }

    @Test
    void getBufferSize_returnsFromResponse() {
        when(response.getBufferSize()).thenReturn(4096);
        assertEquals(4096, servletResponse.getBufferSize());
    }

    @Test
    void setBufferSize_doesNothing() {
        servletResponse.setBufferSize(8192);
    }

    @Test
    void isCommitted_returnsFalse() {
        assertFalse(servletResponse.isCommitted());
    }

    @Test
    void resetBuffer_delegatesToResponse() {
        servletResponse.resetBuffer();
        verify(response).resetBuffer();
    }

    @Test
    void reset_delegatesToResponseResetBuffer() {
        servletResponse.reset();
        verify(response).resetBuffer();
    }

    // Servlet 规范 §5.6：响应已提交（内容已在线路上）后 resetBuffer/reset 必须抛 IllegalStateException——
    // 不得静默清空缓冲，让调用方误以为能收回已发出的内容（forward/sendRedirect 先查 isCommitted，不受影响）。
    @Test
    void resetBuffer_afterCommit_throwsIllegalState() {
        when(response.isCommitted()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> servletResponse.resetBuffer());
        verify(response, never()).resetBuffer();
    }

    @Test
    void reset_afterCommit_throwsIllegalState() {
        when(response.isCommitted()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> servletResponse.reset());
        verify(response, never()).resetBuffer();
    }

    // 无 adapterContext（无请求上下文）时 on-param 三参数均视为未命中 → false,false,false
    @Test
    void sendError_withStatus_delegatesToResponse() {
        servletResponse.sendError(500);
        verify(response).sendError(HttpStatus.valueOf(500), null, null, false, false, false);
    }

    @Test
    void sendError_withStatusAndMessage_delegatesToResponse() {
        servletResponse.sendError(400, "Bad Request");
        verify(response).sendError(HttpStatus.valueOf(400), "Bad Request", null, false, false, false);
    }

    @Test
    void isCommitted_delegatesToResponse() {
        when(response.isCommitted()).thenReturn(true);
        assertTrue(servletResponse.isCommitted());
    }

    @Test
    void isCommitted_notCommitted() {
        when(response.isCommitted()).thenReturn(false);
        assertFalse(servletResponse.isCommitted());
    }

    @Test
    void containsHeader_checksHeaders() {
        when(headers.containsKey("X-Custom")).thenReturn(true);
        assertTrue(servletResponse.containsHeader("X-Custom"));
    }

    @Test
    void containsHeader_missing() {
        when(headers.containsKey("X-Missing")).thenReturn(false);
        assertFalse(servletResponse.containsHeader("X-Missing"));
    }

    @Test
    void setDateHeader_formatsAndSetsHeader() {
        servletResponse.setDateHeader("Date", 0);
        verify(headers).set(eq("Date"), anyString());
    }

    @Test
    void addDateHeader_formatsAndAddsHeader() {
        servletResponse.addDateHeader("Date", 0);
        verify(headers).add(eq("Date"), anyString());
    }

    @Test
    void setIntHeader_convertsAndSetsHeader() {
        servletResponse.setIntHeader("X-Count", 42);
        verify(headers).set("X-Count", "42");
    }

    @Test
    void addIntHeader_convertsAndAddsHeader() {
        servletResponse.addIntHeader("X-Count", 42);
        verify(headers).add("X-Count", "42");
    }

    @Test
    void sendRedirect_sets302AndLocation() {
        io.springperf.web.context.WebContext webContext = mock(io.springperf.web.context.WebContext.class);
        when(response.getWebContext()).thenReturn(webContext);
        when(webContext.getContextPath()).thenReturn("");
        servletResponse.sendRedirect("/target");
        verify(response).setStatusCode(HttpStatus.valueOf(302));
        verify(headers).set(io.netty.handler.codec.http.HttpHeaders.Names.LOCATION, "/target");
    }

    @Test
    void sendRedirect_withContextPath() {
        io.springperf.web.context.WebContext webContext = mock(io.springperf.web.context.WebContext.class);
        when(response.getWebContext()).thenReturn(webContext);
        when(webContext.getContextPath()).thenReturn("/app");
        servletResponse.sendRedirect("/target");
        verify(headers).set(io.netty.handler.codec.http.HttpHeaders.Names.LOCATION, "/app/target");
    }

    @Test
    void sendRedirect_committed_throws() {
        when(response.isCommitted()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> servletResponse.sendRedirect("/target"));
    }

    @Test
    void sendError_committed_throws() {
        when(response.isCommitted()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> servletResponse.sendError(500));
    }

    @Test
    void reset_clearsHeadersAndStatus() {
        servletResponse.reset();
        verify(headers).clear();
        verify(response).setStatusCode(HttpStatus.OK);
        verify(response).resetBuffer();
    }

    @Test
    void setContentLength_setsHeader() {
        servletResponse.setContentLength(100);
        verify(headers).set("Content-Length", "100");
    }

    @Test
    void setContentLengthLong_setsHeader() {
        servletResponse.setContentLengthLong(100L);
        verify(headers).set("Content-Length", "100");
    }

    @Test
    void setContentLength_negative_ignored() {
        servletResponse.setContentLength(-1);
        verify(headers, never()).set(eq("Content-Length"), anyString());
    }

    @Test
    void setLocale_setsContentLanguage() {
        servletResponse.setLocale(java.util.Locale.US);
        verify(headers).set("Content-Language", "en-US");
    }

    @Test
    void getLocale_returnsSetLocale() {
        servletResponse.setLocale(java.util.Locale.CHINA);
        assertEquals(java.util.Locale.CHINA, servletResponse.getLocale());
    }

    @Test
    void getLocale_default() {
        assertEquals(java.util.Locale.getDefault(), servletResponse.getLocale());
    }

    @Test
    void getOutputStream_thenGetWriter_throws() {
        servletResponse.getOutputStream();
        assertThrows(IllegalStateException.class, () -> servletResponse.getWriter());
    }

    @Test
    void getWriter_thenGetOutputStream_throws() throws Exception {
        when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        servletResponse.getWriter();
        assertThrows(IllegalStateException.class, () -> servletResponse.getOutputStream());
    }

    @Test
    void setContentType_extractsCharset() {
        servletResponse.setContentType("text/html; charset=GBK");
        verify(response).setCharacterEncoding(java.nio.charset.Charset.forName("GBK"));
    }

    @Test
    void encodeURL_returnsUrl() {
        assertEquals("/test", servletResponse.encodeURL("/test"));
    }

    @Test
    void encodeRedirectURL_returnsUrl() {
        assertEquals("/test", servletResponse.encodeRedirectURL("/test"));
    }

    @Test
    void flushBuffer_flushesWriterContentAndCommits() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(response.getBody()).thenReturn(baos);
        when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        servletResponse.getWriter().write("servlet content");
        servletResponse.flushBuffer();
        assertEquals("servlet content", baos.toString("UTF-8"), "flushBuffer 应先把 Writer 编码缓冲刷入响应体");
        verify(response).flushChunked();
    }

    @Test
    void flushBuffer_withoutWriter_stillCommits() throws Exception {
        servletResponse.flushBuffer();
        verify(response).flushChunked();
    }

    @Test
    void sendRedirect_nullLocation_throws() {
        assertThrows(IllegalArgumentException.class, () -> servletResponse.sendRedirect(null));
    }

    @Test
    void sendRedirect_absoluteLocation_noContextRewrite() {
        servletResponse.sendRedirect("https://external.com/page");
        verify(response).setStatusCode(HttpStatus.valueOf(302));
        verify(headers).set(io.netty.handler.codec.http.HttpHeaders.Names.LOCATION, "https://external.com/page");
    }

    @Test
    void sendRedirect_withAdapterContext_absoluteUrlWithPort() {
        io.springperf.web.context.WebContext webContext = mock(io.springperf.web.context.WebContext.class);
        when(response.getWebContext()).thenReturn(webContext);
        when(webContext.getContextPath()).thenReturn("/app");
        ServletAdapterContext adapter = mock(ServletAdapterContext.class);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(adapter.getRequest()).thenReturn(httpRequest);
        when(httpRequest.getScheme()).thenReturn("http");
        when(httpRequest.getServerName()).thenReturn("localhost");
        when(httpRequest.getServerPort()).thenReturn(8080);
        servletResponse.setAdapterContext(adapter);

        servletResponse.sendRedirect("/target");

        verify(headers).set(io.netty.handler.codec.http.HttpHeaders.Names.LOCATION, "http://localhost:8080/app/target");
    }

    @Test
    void sendRedirect_withAdapterContext_defaultHttpPortOmitted() {
        io.springperf.web.context.WebContext webContext = mock(io.springperf.web.context.WebContext.class);
        when(response.getWebContext()).thenReturn(webContext);
        when(webContext.getContextPath()).thenReturn("");
        ServletAdapterContext adapter = mock(ServletAdapterContext.class);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(adapter.getRequest()).thenReturn(httpRequest);
        when(httpRequest.getScheme()).thenReturn("http");
        when(httpRequest.getServerName()).thenReturn("localhost");
        when(httpRequest.getServerPort()).thenReturn(80);
        servletResponse.setAdapterContext(adapter);

        servletResponse.sendRedirect("/ok");

        verify(headers).set(io.netty.handler.codec.http.HttpHeaders.Names.LOCATION, "http://localhost/ok");
    }

    @Test
    void sendRedirect_withAdapterContext_httpsDefaultPortOmitted() {
        io.springperf.web.context.WebContext webContext = mock(io.springperf.web.context.WebContext.class);
        when(response.getWebContext()).thenReturn(webContext);
        when(webContext.getContextPath()).thenReturn("/app");
        ServletAdapterContext adapter = mock(ServletAdapterContext.class);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(adapter.getRequest()).thenReturn(httpRequest);
        when(httpRequest.getScheme()).thenReturn("https");
        when(httpRequest.getServerName()).thenReturn("secure.example");
        when(httpRequest.getServerPort()).thenReturn(443);
        servletResponse.setAdapterContext(adapter);

        servletResponse.sendRedirect("/s");

        verify(headers).set(io.netty.handler.codec.http.HttpHeaders.Names.LOCATION, "https://secure.example/app/s");
    }

    @Test
    void sendError_withMessage_committed_throws() {
        when(response.isCommitted()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> servletResponse.sendError(500, "oops"));
    }

    // ---- sendRedirect：相对 location 必须由容器转成绝对 URL（Servlet 规范 sendRedirect 契约） ----

    @Test
    void sendRedirect_relativeLocation_resolvedAgainstRequestDirectory() {
        servletResponse.setAdapterContext(adapterFor("http", "localhost", 8080, "/app/forms/edit"));

        servletResponse.sendRedirect("next");

        verify(headers).set(io.netty.handler.codec.http.HttpHeaders.Names.LOCATION,
                "http://localhost:8080/app/forms/next");
    }

    @Test
    void sendRedirect_relativeLocation_parentSegmentNormalisedAndQueryKept() {
        servletResponse.setAdapterContext(adapterFor("http", "localhost", 8080, "/app/forms/edit"));

        servletResponse.sendRedirect("../list?page=2");

        verify(headers).set(io.netty.handler.codec.http.HttpHeaders.Names.LOCATION,
                "http://localhost:8080/app/list?page=2");
    }

    @Test
    void sendRedirect_relativeLocation_fragmentKept() {
        servletResponse.setAdapterContext(adapterFor("http", "localhost", 8080, "/app/edit"));

        servletResponse.sendRedirect("next#top");

        verify(headers).set(io.netty.handler.codec.http.HttpHeaders.Names.LOCATION,
                "http://localhost:8080/app/next#top");
    }

    @Test
    void sendRedirect_relativeLocation_neverEscapesRoot() {
        servletResponse.setAdapterContext(adapterFor("http", "localhost", 8080, "/edit"));

        servletResponse.sendRedirect("../../a");

        verify(headers).set(io.netty.handler.codec.http.HttpHeaders.Names.LOCATION, "http://localhost:8080/a");
    }

    @Test
    void sendRedirect_networkPathReference_untouched() {
        servletResponse.sendRedirect("//cdn.example/lib.js");

        verify(headers).set(io.netty.handler.codec.http.HttpHeaders.Names.LOCATION, "//cdn.example/lib.js");
    }

    @Test
    void sendRedirect_relativeLocation_withoutRequestContext_staysRootRelative() {
        servletResponse.sendRedirect("next");

        verify(headers).set(io.netty.handler.codec.http.HttpHeaders.Names.LOCATION, "/next");
    }

    // ---- sendError：include-* 的 on-param 模式需按当前请求参数决定是否透出 ----

    @Test
    void sendError_messageParam_present_includesMessage() {
        servletResponse.setAdapterContext(adapterWithParams("message", "true"));

        servletResponse.sendError(500, "boom");

        // 参数顺序：status, message, cause, includeStacktraceOnParam, includeMessageOnParam,
        // includeBindingErrorsOnParam
        verify(response).sendError(HttpStatus.INTERNAL_SERVER_ERROR, "boom", null, false, true, false);
    }

    @Test
    void sendError_paramExplicitlyFalse_countsAsAbsent() {
        servletResponse.setAdapterContext(adapterWithParams("message", "false"));

        servletResponse.sendError(500, "boom");

        verify(response).sendError(HttpStatus.INTERNAL_SERVER_ERROR, "boom", null, false, false, false);
    }

    @Test
    void sendError_traceAndErrorsParams_forwarded() {
        servletResponse.setAdapterContext(adapterWithParams("trace", "", "errors", "true"));

        servletResponse.sendError(400);

        verify(response).sendError(HttpStatus.BAD_REQUEST, null, null, true, false, true);
    }

    // ---- setCharacterEncoding：force-response=true 时忽略业务覆盖 ----

    @Test
    void setCharacterEncoding_forceResponse_ignored() {
        PerfServletContext ctx = mock(PerfServletContext.class);
        when(ctx.isForceResponseEncoding()).thenReturn(true);
        PerfHttpServletRequest perfRequest = mock(PerfHttpServletRequest.class);
        when(perfRequest.getServletContext()).thenReturn(ctx);
        ServletAdapterContext adapter = mock(ServletAdapterContext.class);
        when(adapter.getPerfRequest()).thenReturn(perfRequest);
        servletResponse.setAdapterContext(adapter);

        servletResponse.setCharacterEncoding("GBK");

        verify(response, never()).setCharacterEncoding(any(java.nio.charset.Charset.class));
    }

    // ---- 容器级响应编码：server.servlet.encoding.charset 在适配器创建期写入，构成业务可覆盖的默认值 ----

    @Test
    void constructor_appliesContainerResponseEncoding() {
        io.springperf.web.context.WebContext webContext = mock(io.springperf.web.context.WebContext.class);
        when(response.getWebContext()).thenReturn(webContext);
        PerfServletContext servletContext = mock(PerfServletContext.class);
        when(servletContext.getResponseCharacterEncoding()).thenReturn("GBK");
        when(webContext.getWebComponent(PerfServletContext.class)).thenReturn(servletContext);

        new PerfHttpServletResponse(response);

        verify(response).setCharacterEncoding(java.nio.charset.Charset.forName("GBK"));
    }

    @Test
    void encodeURL_null_returnsNull() {
        assertNull(servletResponse.encodeURL(null));
    }

    @Test
    void encodeURL_noAdapterContext_unchanged() {
        assertEquals("/path", servletResponse.encodeURL("/path"));
    }

    @Test
    void encodeURL_sessionFromCookie_unchanged() {
        ServletAdapterContext adapter = mock(ServletAdapterContext.class);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        ServletContext servletContext = mock(ServletContext.class);
        when(adapter.getRequest()).thenReturn(httpRequest);
        when(httpRequest.getServletContext()).thenReturn(servletContext);
        when(servletContext.getEffectiveSessionTrackingModes())
                .thenReturn(java.util.EnumSet.of(SessionTrackingMode.URL));
        when(httpRequest.isRequestedSessionIdFromCookie()).thenReturn(true);
        servletResponse.setAdapterContext(adapter);

        assertEquals("/path", servletResponse.encodeURL("/path"));
    }

    @Test
    void encodeURL_noRequestedSessionId_unchanged() {
        ServletAdapterContext adapter = mock(ServletAdapterContext.class);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        ServletContext servletContext = mock(ServletContext.class);
        when(adapter.getRequest()).thenReturn(httpRequest);
        when(httpRequest.getServletContext()).thenReturn(servletContext);
        when(servletContext.getEffectiveSessionTrackingModes())
                .thenReturn(java.util.EnumSet.of(SessionTrackingMode.URL));
        when(httpRequest.isRequestedSessionIdFromCookie()).thenReturn(false);
        when(httpRequest.getRequestedSessionId()).thenReturn(null);
        servletResponse.setAdapterContext(adapter);

        assertEquals("/path", servletResponse.encodeURL("/path"));
    }

    @Test
    void encodeURL_alreadyContainsJSessionId_unchanged() {
        ServletAdapterContext adapter = mock(ServletAdapterContext.class);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        ServletContext servletContext = mock(ServletContext.class);
        when(adapter.getRequest()).thenReturn(httpRequest);
        when(httpRequest.getServletContext()).thenReturn(servletContext);
        when(servletContext.getEffectiveSessionTrackingModes())
                .thenReturn(java.util.EnumSet.of(SessionTrackingMode.URL));
        when(httpRequest.isRequestedSessionIdFromCookie()).thenReturn(false);
        when(httpRequest.getRequestedSessionId()).thenReturn("abc");
        servletResponse.setAdapterContext(adapter);

        assertEquals("/path;jsessionid=abc", servletResponse.encodeURL("/path;jsessionid=abc"));
    }

    @Test
    void encodeURL_appendsJSessionId_beforeQueryAndFragment() {
        ServletAdapterContext adapter = mock(ServletAdapterContext.class);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        ServletContext servletContext = mock(ServletContext.class);
        when(adapter.getRequest()).thenReturn(httpRequest);
        when(httpRequest.getServletContext()).thenReturn(servletContext);
        when(servletContext.getEffectiveSessionTrackingModes())
                .thenReturn(java.util.EnumSet.of(SessionTrackingMode.URL));
        when(httpRequest.isRequestedSessionIdFromCookie()).thenReturn(false);
        when(httpRequest.getRequestedSessionId()).thenReturn("sess123");
        servletResponse.setAdapterContext(adapter);

        assertEquals("/path;jsessionid=sess123?a=1#frag", servletResponse.encodeURL("/path?a=1#frag"));
    }

    @Test
    void encodeRedirectURL_appendsJSessionId() {
        ServletAdapterContext adapter = mock(ServletAdapterContext.class);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        ServletContext servletContext = mock(ServletContext.class);
        when(adapter.getRequest()).thenReturn(httpRequest);
        when(httpRequest.getServletContext()).thenReturn(servletContext);
        when(servletContext.getEffectiveSessionTrackingModes())
                .thenReturn(java.util.EnumSet.of(SessionTrackingMode.URL));
        when(httpRequest.isRequestedSessionIdFromCookie()).thenReturn(false);
        when(httpRequest.getRequestedSessionId()).thenReturn("sess456");
        servletResponse.setAdapterContext(adapter);

        assertEquals("/target;jsessionid=sess456", servletResponse.encodeRedirectURL("/target"));
    }

    @Test
    void encodeURL_urlTrackingDisabled_unchanged() {
        ServletAdapterContext adapter = mock(ServletAdapterContext.class);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        ServletContext servletContext = mock(ServletContext.class);
        when(adapter.getRequest()).thenReturn(httpRequest);
        when(httpRequest.getServletContext()).thenReturn(servletContext);
        // 默认仅 COOKIE：即使无 cookie 也不重写 URL（提前返回，不再读 session id）
        when(servletContext.getEffectiveSessionTrackingModes())
                .thenReturn(java.util.Collections.singleton(SessionTrackingMode.COOKIE));
        servletResponse.setAdapterContext(adapter);

        assertEquals("/path", servletResponse.encodeURL("/path"));
    }

    @Test
    void encodeRedirectURL_urlTrackingDisabled_unchanged() {
        ServletAdapterContext adapter = mock(ServletAdapterContext.class);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        ServletContext servletContext = mock(ServletContext.class);
        when(adapter.getRequest()).thenReturn(httpRequest);
        when(httpRequest.getServletContext()).thenReturn(servletContext);
        when(servletContext.getEffectiveSessionTrackingModes())
                .thenReturn(java.util.Collections.singleton(SessionTrackingMode.COOKIE));
        servletResponse.setAdapterContext(adapter);

        assertEquals("/target", servletResponse.encodeRedirectURL("/target"));
    }

    @Test
    void addCookie_encodesToSetCookieHeader() {
        Cookie cookie = new Cookie("name", "value");
        servletResponse.addCookie(cookie);

        verify(headers).add(argThat(name -> "Set-Cookie".equals(name)), argThat(value -> value.contains("name=value")));
    }

    @Test
    void addCookie_withAllAttributes() {
        Cookie cookie = new Cookie("session", "token");
        cookie.setDomain("example.com");
        cookie.setPath("/app");
        cookie.setMaxAge(3600);
        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        servletResponse.addCookie(cookie);

        verify(headers).add(argThat(name -> "Set-Cookie".equals(name)),
                argThat(value -> value.contains("session=token") && value.contains("Domain=example.com")
                        && value.contains("Path=/app") && value.contains("Max-Age=3600") && value.contains("Secure")
                        && value.contains("HTTPOnly")));
    }

    @Test
    void addCookie_nullValue_becomesEmpty() {
        Cookie cookie = new Cookie("name", null);
        servletResponse.addCookie(cookie);
        verify(headers).add(argThat(name -> "Set-Cookie".equals(name)), argThat(value -> value.contains("name=")));
    }

    @Test
    void addCookie_withSameSite() {
        servletResponse.setSameSite("Lax");
        Cookie cookie = new Cookie("name", "value");
        servletResponse.addCookie(cookie);
        verify(headers).add(argThat(name -> "Set-Cookie".equals(name)),
                argThat(value -> value.contains("SameSite=Lax")));
    }

    @Test
    void rebind_sameResponse_keepsWriterCache() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(response.getBody()).thenReturn(baos);
        when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);

        servletResponse.getWriter().write("x");
        servletResponse.flushBuffer();
        assertEquals("x", baos.toString("UTF-8"));

        servletResponse.rebind(response);
        assertSame(response, servletResponse.getResponse());
    }

    @Test
    void rebind_newResponse_resetsWriterCacheAndDelegates() throws Exception {
        ByteArrayOutputStream baos1 = new ByteArrayOutputStream();
        when(response.getBody()).thenReturn(baos1);
        when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        servletResponse.getWriter().write("one");
        servletResponse.flushBuffer();
        assertEquals("one", baos1.toString("UTF-8"));

        WebServerHttpResponse newResponse = mock(WebServerHttpResponse.class);
        ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
        when(newResponse.getBody()).thenReturn(baos2);
        when(newResponse.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        servletResponse.rebind(newResponse);

        servletResponse.getWriter().write("two");
        servletResponse.flushBuffer();
        assertEquals("two", baos2.toString("UTF-8"));
        assertSame(newResponse, servletResponse.getResponse());
    }

    @Test
    void getOutputStream_multipleCalls_allowed() throws Exception {
        assertNotNull(servletResponse.getOutputStream());
        assertNotNull(servletResponse.getOutputStream());
    }

    /** 构造只提供 scheme/host/port/requestURI 的适配器上下文（用于相对 location 解析）。 */
    private ServletAdapterContext adapterFor(String scheme, String serverName, int port, String requestUri) {
        ServletAdapterContext adapter = mock(ServletAdapterContext.class);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(adapter.getRequest()).thenReturn(httpRequest);
        when(httpRequest.getScheme()).thenReturn(scheme);
        when(httpRequest.getServerName()).thenReturn(serverName);
        when(httpRequest.getServerPort()).thenReturn(port);
        when(httpRequest.getRequestURI()).thenReturn(requestUri);
        return adapter;
    }

    /**
     * 构造带请求参数表的适配器上下文（用于 sendError 的 on-param 透传）。
     * <p>
     * {@code sendError} 会依次询问 trace / message / errors 三个参数，未列出的必须显式打桩为 {@code null}， 否则 Mockito 的严格桩校验会以
     * {@code PotentialStubbingProblem} 拒绝执行。
     * </p>
     */
    private ServletAdapterContext adapterWithParams(String... nameValuePairs) {
        ServletAdapterContext adapter = mock(ServletAdapterContext.class);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(adapter.getRequest()).thenReturn(httpRequest);
        lenient().when(httpRequest.getParameter(anyString())).thenReturn(null);
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            when(httpRequest.getParameter(nameValuePairs[i])).thenReturn(nameValuePairs[i + 1]);
        }
        return adapter;
    }
}

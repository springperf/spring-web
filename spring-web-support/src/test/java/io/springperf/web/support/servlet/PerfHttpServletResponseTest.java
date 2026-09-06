package io.springperf.web.support.servlet;

import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
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

    @Mock WebServerHttpResponse response;
    @Mock HttpHeaders headers;

    private PerfHttpServletResponse servletResponse;

    @BeforeEach
    void setUp() {
        lenient().when(response.getHeaders()).thenReturn(headers);
        servletResponse = new PerfHttpServletResponse(response);
    }

    @Test void setStatus_delegatesStatusCode() { servletResponse.setStatus(404); verify(response).setStatusCode(HttpStatus.valueOf(404)); }
    @Test void getStatus_returnsStatusValue() { when(response.getStatus()).thenReturn(HttpStatus.CREATED); assertEquals(201, servletResponse.getStatus()); }
    @Test void setHeader_delegatesToHeadersSet() { servletResponse.setHeader("X-Custom", "value"); verify(headers).set("X-Custom", "value"); }
    @Test void addHeader_delegatesToHeadersAdd() { servletResponse.addHeader("X-Custom", "value"); verify(headers).add("X-Custom", "value"); }
    @Test void getHeader_returnsFirstValue() { when(headers.getFirst("X-Custom")).thenReturn("value"); assertEquals("value", servletResponse.getHeader("X-Custom")); }
    @Test void getHeaders_returnsCollection() { java.util.Collection<String> values = java.util.Arrays.asList("a", "b"); when(headers.get("X-Custom")).thenReturn((java.util.List<String>) (java.util.List) values); assertTrue(servletResponse.getHeaders("X-Custom").containsAll(values)); }
    @Test void getHeaderNames_returnsSet() { java.util.Set<String> names = new java.util.HashSet<>(java.util.Arrays.asList("Content-Type", "X-Custom")); when(headers.keySet()).thenReturn(names); assertEquals(names, servletResponse.getHeaderNames()); }
    @Test void setContentType_delegatesToSetHeader() { servletResponse.setContentType("application/json"); verify(headers).set("Content-Type", "application/json"); }
    @Test void getContentType_returnsHeaderValue() { when(headers.getFirst("Content-Type")).thenReturn("text/html"); assertEquals("text/html", servletResponse.getContentType()); }
    @Test void getCharacterEncoding_returnsFromResponse() { when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8); assertEquals("UTF-8", servletResponse.getCharacterEncoding()); }
    @Test void getCharacterEncoding_null_returnsNull() { when(response.getCharacterEncoding()).thenReturn(null); assertNull(servletResponse.getCharacterEncoding()); }
    @Test void setCharacterEncoding_delegatesToResponse() { servletResponse.setCharacterEncoding("ISO-8859-1"); verify(response).setCharacterEncoding(StandardCharsets.ISO_8859_1); }
    @Test void getOutputStream_writesToResponseBody() throws Exception { ByteArrayOutputStream baos = new ByteArrayOutputStream(); when(response.getBody()).thenReturn(baos); ServletOutputStream out = servletResponse.getOutputStream(); out.write(65); out.write("hello".getBytes()); assertArrayEquals(new byte[]{65, 'h', 'e', 'l', 'l', 'o'}, baos.toByteArray()); }
    @Test void getOutputStream_print_writesEncodedString() throws Exception { ByteArrayOutputStream baos = new ByteArrayOutputStream(); when(response.getBody()).thenReturn(baos); when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8); servletResponse.getOutputStream().print("hello"); assertEquals("hello", baos.toString("UTF-8")); }
    @Test void getOutputStream_flush_flushesBody() throws Exception { ByteArrayOutputStream baos = new ByteArrayOutputStream(); when(response.getBody()).thenReturn(baos); servletResponse.getOutputStream().flush(); }
    @Test void getOutputStream_setWriteListener_throwsUnsupported() throws Exception { assertThrows(UnsupportedOperationException.class, () -> servletResponse.getOutputStream().setWriteListener(null)); }
    @Test void getOutputStream_isReady_returnsTrue() throws Exception { assertTrue(servletResponse.getOutputStream().isReady()); }
    @Test void getWriter_writesToResponseBody() throws Exception { ByteArrayOutputStream baos = new ByteArrayOutputStream(); when(response.getBody()).thenReturn(baos); when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8); PrintWriter writer = servletResponse.getWriter(); writer.print("test content"); writer.flush(); assertEquals("test content", baos.toString("UTF-8")); }
    @Test void flushBuffer_delegatesToResponseFlush() throws Exception { servletResponse.flushBuffer(); verify(response).flush(); }
    @Test void getBufferSize_returnsFromResponse() { when(response.getBufferSize()).thenReturn(4096); assertEquals(4096, servletResponse.getBufferSize()); }
    @Test void setBufferSize_doesNothing() { servletResponse.setBufferSize(8192); }
    @Test void isCommitted_returnsFalse() { assertFalse(servletResponse.isCommitted()); }
    @Test void resetBuffer_delegatesToResponse() { servletResponse.resetBuffer(); verify(response).resetBuffer(); }
    @Test void reset_delegatesToResponseResetBuffer() { servletResponse.reset(); verify(response).resetBuffer(); }
    @Test void sendError_withStatus_delegatesToResponse() { servletResponse.sendError(500); verify(response).sendError(HttpStatus.valueOf(500)); }
    @Test void sendError_withStatusAndMessage_delegatesToResponse() { servletResponse.sendError(400, "Bad Request"); verify(response).sendError(HttpStatus.valueOf(400), "Bad Request"); }

    @Test void isCommitted_delegatesToResponse() { when(response.isCommitted()).thenReturn(true); assertTrue(servletResponse.isCommitted()); }
    @Test void isCommitted_notCommitted() { when(response.isCommitted()).thenReturn(false); assertFalse(servletResponse.isCommitted()); }
    @Test void containsHeader_checksHeaders() { when(headers.containsKey("X-Custom")).thenReturn(true); assertTrue(servletResponse.containsHeader("X-Custom")); }
    @Test void containsHeader_missing() { when(headers.containsKey("X-Missing")).thenReturn(false); assertFalse(servletResponse.containsHeader("X-Missing")); }
    @Test void setDateHeader_formatsAndSetsHeader() { servletResponse.setDateHeader("Date", 0); verify(headers).set(eq("Date"), anyString()); }
    @Test void addDateHeader_formatsAndAddsHeader() { servletResponse.addDateHeader("Date", 0); verify(headers).add(eq("Date"), anyString()); }
    @Test void setIntHeader_convertsAndSetsHeader() { servletResponse.setIntHeader("X-Count", 42); verify(headers).set("X-Count", "42"); }
    @Test void addIntHeader_convertsAndAddsHeader() { servletResponse.addIntHeader("X-Count", 42); verify(headers).add("X-Count", "42"); }

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
    void setContentLength_setsHeader() { servletResponse.setContentLength(100); verify(headers).set("Content-Length", "100"); }
    @Test
    void setContentLengthLong_setsHeader() { servletResponse.setContentLengthLong(100L); verify(headers).set("Content-Length", "100"); }
    @Test
    void setContentLength_negative_ignored() { servletResponse.setContentLength(-1); verify(headers, never()).set(eq("Content-Length"), anyString()); }

    @Test
    void setLocale_setsContentLanguage() { servletResponse.setLocale(java.util.Locale.US); verify(headers).set("Content-Language", "en-US"); }
    @Test
    void getLocale_returnsSetLocale() { servletResponse.setLocale(java.util.Locale.CHINA); assertEquals(java.util.Locale.CHINA, servletResponse.getLocale()); }
    @Test
    void getLocale_default() { assertEquals(java.util.Locale.getDefault(), servletResponse.getLocale()); }

    @Test
    void getOutputStream_thenGetWriter_throws() {
        servletResponse.getOutputStream();
        assertThrows(IllegalStateException.class, () -> servletResponse.getWriter());
    }

    @Test
    void getWriter_thenGetOutputStream_throws() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(response.getBody()).thenReturn(baos);
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
    void flushBuffer_flushesWriterContentAndResponse() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        when(response.getBody()).thenReturn(baos);
        when(response.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        servletResponse.getWriter().write("servlet content");
        servletResponse.flushBuffer();
        assertEquals("servlet content", baos.toString("UTF-8"));
        verify(response).flush();
    }

    @Test
    void flushBuffer_withoutWriter_stillFlushesResponse() throws Exception {
        servletResponse.flushBuffer();
        verify(response).flush();
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

        verify(headers).set(io.netty.handler.codec.http.HttpHeaders.Names.LOCATION,
                "http://localhost:8080/app/target");
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

        verify(headers).set(io.netty.handler.codec.http.HttpHeaders.Names.LOCATION,
                "https://secure.example/app/s");
    }

    @Test
    void sendError_withMessage_committed_throws() {
        when(response.isCommitted()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> servletResponse.sendError(500, "oops"));
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
        when(adapter.getRequest()).thenReturn(httpRequest);
        when(httpRequest.isRequestedSessionIdFromCookie()).thenReturn(true);
        servletResponse.setAdapterContext(adapter);

        assertEquals("/path", servletResponse.encodeURL("/path"));
    }

    @Test
    void encodeURL_noRequestedSessionId_unchanged() {
        ServletAdapterContext adapter = mock(ServletAdapterContext.class);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(adapter.getRequest()).thenReturn(httpRequest);
        when(httpRequest.isRequestedSessionIdFromCookie()).thenReturn(false);
        when(httpRequest.getRequestedSessionId()).thenReturn(null);
        servletResponse.setAdapterContext(adapter);

        assertEquals("/path", servletResponse.encodeURL("/path"));
    }

    @Test
    void encodeURL_alreadyContainsJSessionId_unchanged() {
        ServletAdapterContext adapter = mock(ServletAdapterContext.class);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(adapter.getRequest()).thenReturn(httpRequest);
        when(httpRequest.isRequestedSessionIdFromCookie()).thenReturn(false);
        when(httpRequest.getRequestedSessionId()).thenReturn("abc");
        servletResponse.setAdapterContext(adapter);

        assertEquals("/path;jsessionid=abc", servletResponse.encodeURL("/path;jsessionid=abc"));
    }

    @Test
    void encodeURL_appendsJSessionId_beforeQueryAndFragment() {
        ServletAdapterContext adapter = mock(ServletAdapterContext.class);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(adapter.getRequest()).thenReturn(httpRequest);
        when(httpRequest.isRequestedSessionIdFromCookie()).thenReturn(false);
        when(httpRequest.getRequestedSessionId()).thenReturn("sess123");
        servletResponse.setAdapterContext(adapter);

        assertEquals("/path;jsessionid=sess123?a=1#frag",
                servletResponse.encodeURL("/path?a=1#frag"));
    }

    @Test
    void encodeRedirectURL_appendsJSessionId() {
        ServletAdapterContext adapter = mock(ServletAdapterContext.class);
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(adapter.getRequest()).thenReturn(httpRequest);
        when(httpRequest.isRequestedSessionIdFromCookie()).thenReturn(false);
        when(httpRequest.getRequestedSessionId()).thenReturn("sess456");
        servletResponse.setAdapterContext(adapter);

        assertEquals("/target;jsessionid=sess456", servletResponse.encodeRedirectURL("/target"));
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
                argThat(value -> value.contains("session=token")
                        && value.contains("Domain=example.com")
                        && value.contains("Path=/app")
                        && value.contains("Max-Age=3600")
                        && value.contains("Secure")
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
}

package io.springperf.web.support.servlet;

import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletResponse;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class AbstractFastFailHttpServletResponseTest {

    private final AbstractFastFailHttpServletResponse response = new AbstractFastFailHttpServletResponse() {};

    @Test
    void getCharacterEncoding_returnsUtf8() {
        assertEquals("UTF-8", response.getCharacterEncoding());
    }

    @Test
    void getContentType_returnsNull() {
        assertNull(response.getContentType());
    }

    @Test
    void getBufferSize_returnsZero() {
        assertEquals(0, response.getBufferSize());
    }

    @Test
    void isCommitted_returnsFalse() {
        assertFalse(response.isCommitted());
    }

    @Test
    void getLocale_returnsDefault() {
        assertEquals(Locale.getDefault(), response.getLocale());
    }

    @Test
    void containsHeader_returnsFalse() {
        assertFalse(response.containsHeader("any"));
    }

    @Test
    void encodeURL_passthrough() {
        assertEquals("/path", response.encodeURL("/path"));
    }

    @Test
    void encodeRedirectURL_passthrough() {
        assertEquals("/path", response.encodeRedirectURL("/path"));
    }

    @Test
    void getStatus_returnsOk() {
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    }

    @Test
    void getHeader_returnsNull() {
        assertNull(response.getHeader("any"));
    }

    @Test
    void getHeaders_returnsEmpty() {
        assertTrue(response.getHeaders("any").isEmpty());
    }

    @Test
    void getHeaderNames_returnsEmpty() {
        assertTrue(response.getHeaderNames().isEmpty());
    }

    @Test
    void ignoreMethods_doNotThrow() throws Exception {
        response.setCharacterEncoding("GBK");
        response.setContentLength(100);
        response.setContentLengthLong(100L);
        response.setContentType("text/plain");
        response.setBufferSize(1024);
        response.flushBuffer();
        response.setLocale(Locale.CHINA);

        // no-op setter 不得改变任何可观察状态，也不得让快速失败的方法变成可用
        assertEquals("UTF-8", response.getCharacterEncoding());
        assertNull(response.getContentType());
        assertEquals(0, response.getBufferSize());
        assertFalse(response.isCommitted());
        assertEquals(Locale.getDefault(), response.getLocale());
        assertTrue(response.getHeaderNames().isEmpty());
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
        assertThrows(UnsupportedOperationException.class, response::getWriter);
    }

    @Test
    void unsupportedMethods_throwException() {
        assertThrows(UnsupportedOperationException.class, () -> response.getOutputStream());
        assertThrows(UnsupportedOperationException.class, () -> response.getWriter());
        assertThrows(UnsupportedOperationException.class, () -> response.resetBuffer());
        assertThrows(UnsupportedOperationException.class, () -> response.reset());
        assertThrows(UnsupportedOperationException.class, () -> response.addCookie(null));
        assertThrows(UnsupportedOperationException.class, () -> response.sendError(404));
        assertThrows(UnsupportedOperationException.class, () -> response.sendError(404, "Not Found"));
        assertThrows(UnsupportedOperationException.class, () -> response.sendRedirect("/"));
        assertThrows(UnsupportedOperationException.class, () -> response.setHeader("X", "v"));
        assertThrows(UnsupportedOperationException.class, () -> response.addHeader("X", "v"));
        assertThrows(UnsupportedOperationException.class, () -> response.setStatus(200));
        assertThrows(UnsupportedOperationException.class, () -> response.setDateHeader("X", 0));
        assertThrows(UnsupportedOperationException.class, () -> response.addDateHeader("X", 0));
    }
}

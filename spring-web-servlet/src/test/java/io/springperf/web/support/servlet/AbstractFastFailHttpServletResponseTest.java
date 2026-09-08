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
    void ignoreMethods_doNotThrow() {
        assertDoesNotThrow(() -> response.setCharacterEncoding("UTF-8"));
        assertDoesNotThrow(() -> response.setContentLength(100));
        assertDoesNotThrow(() -> response.setContentLengthLong(100L));
        assertDoesNotThrow(() -> response.setContentType("text/plain"));
        assertDoesNotThrow(() -> response.setBufferSize(1024));
        assertDoesNotThrow(() -> response.flushBuffer());
        assertDoesNotThrow(() -> response.setLocale(Locale.CHINA));
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

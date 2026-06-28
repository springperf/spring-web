package io.springperf.web.support.servlet;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class AbstractFastFailHttpServletRequestTest {

    private final AbstractFastFailHttpServletRequest request = new AbstractFastFailHttpServletRequest() {};

    @Test
    void getCharacterEncoding_returnsUtf8() {
        assertEquals("UTF-8", request.getCharacterEncoding());
    }

    @Test
    void getContentLength_returnsMinusOne() {
        assertEquals(-1, request.getContentLength());
    }

    @Test
    void getContentLengthLong_returnsMinusOne() {
        assertEquals(-1L, request.getContentLengthLong());
    }

    @Test
    void getContentType_returnsNull() {
        assertNull(request.getContentType());
    }

    @Test
    void getParameter_returnsNull() {
        assertNull(request.getParameter("any"));
    }

    @Test
    void getParameterMap_returnsEmpty() {
        assertTrue(request.getParameterMap().isEmpty());
    }

    @Test
    void getParameterNames_returnsEmpty() {
        assertFalse(request.getParameterNames().hasMoreElements());
    }

    @Test
    void getParameterValues_returnsNull() {
        assertNull(request.getParameterValues("any"));
    }

    @Test
    void getProtocol_returnsHttp11() {
        assertEquals("HTTP/1.1", request.getProtocol());
    }

    @Test
    void getScheme_returnsHttp() {
        assertEquals("http", request.getScheme());
    }

    @Test
    void getServerPort_returnsMinusOne() {
        assertEquals(-1, request.getServerPort());
    }

    @Test
    void isSecure_returnsFalse() {
        assertFalse(request.isSecure());
    }

    @Test
    void isAsyncStarted_returnsFalse() {
        assertFalse(request.isAsyncStarted());
    }

    @Test
    void isAsyncSupported_returnsFalse() {
        assertFalse(request.isAsyncSupported());
    }

    @Test
    void getDispatcherType_returnsRequest() {
        assertEquals(DispatcherType.REQUEST, request.getDispatcherType());
    }

    @Test
    void getLocale_returnsDefault() {
        assertEquals(Locale.getDefault(), request.getLocale());
    }

    @Test
    void getLocales_containsDefault() {
        assertTrue(request.getLocales().hasMoreElements());
    }

    @Test
    void getCookies_returnsEmptyArray() {
        assertEquals(0, request.getCookies().length);
    }

    @Test
    void getDateHeader_returnsMinusOne() {
        assertEquals(-1L, request.getDateHeader("any"));
    }

    @Test
    void getHeader_returnsNull() {
        assertNull(request.getHeader("any"));
    }

    @Test
    void getHeaders_returnsEmpty() {
        assertFalse(request.getHeaders("any").hasMoreElements());
    }

    @Test
    void getHeaderNames_returnsEmpty() {
        assertFalse(request.getHeaderNames().hasMoreElements());
    }

    @Test
    void getIntHeader_returnsMinusOne() {
        assertEquals(-1, request.getIntHeader("any"));
    }

    @Test
    void getContextPath_returnsEmpty() {
        assertEquals("", request.getContextPath());
    }

    @Test
    void getServletPath_returnsEmpty() {
        assertEquals("", request.getServletPath());
    }

    @Test
    void isUserInRole_returnsFalse() {
        assertFalse(request.isUserInRole("any"));
    }

    @Test
    void getLocalPort_returnsMinusOne() {
        assertEquals(-1, request.getLocalPort());
    }

    @Test
    void getRemotePort_returnsMinusOne() {
        assertEquals(-1, request.getRemotePort());
    }

    @Test
    void unsupportedMethods_throwException() {
        assertThrows(UnsupportedOperationException.class, () -> request.getAttribute("any"));
        assertThrows(UnsupportedOperationException.class, () -> request.getAttributeNames());
        assertThrows(UnsupportedOperationException.class, () -> request.getInputStream());
        assertThrows(UnsupportedOperationException.class, () -> request.getReader());
        assertThrows(UnsupportedOperationException.class, () -> request.getMethod());
        assertThrows(UnsupportedOperationException.class, () -> request.getRequestURI());
        assertThrows(UnsupportedOperationException.class, () -> request.getRequestURL());
        assertThrows(UnsupportedOperationException.class, () -> request.getSession());
        assertThrows(UnsupportedOperationException.class, () -> request.getSession(true));
        assertThrows(UnsupportedOperationException.class, () -> request.authenticate(null));
        assertThrows(UnsupportedOperationException.class, () -> request.getParts());
    }
}

package io.springperf.web.support.servlet;

import org.junit.jupiter.api.Test;

import javax.servlet.DispatcherType;
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

    @Test
    void allFastFailMethods_verifySelfContainedBehavior() {
        // ServletRequest
        assertThrows(UnsupportedOperationException.class, () -> request.setCharacterEncoding("UTF-8"));
        assertThrows(UnsupportedOperationException.class, () -> request.getServletContext());
        assertThrows(UnsupportedOperationException.class, () -> request.startAsync());
        assertThrows(UnsupportedOperationException.class, () -> request.startAsync(null, null));
        assertThrows(UnsupportedOperationException.class, () -> request.getAsyncContext());
        assertThrows(UnsupportedOperationException.class, () -> request.setAttribute("k", "v"));
        assertThrows(UnsupportedOperationException.class, () -> request.removeAttribute("k"));
        assertThrows(UnsupportedOperationException.class, () -> request.getRequestDispatcher("/x"));

        // 杩斿洖 null 鐨勮闂櫒涓嶅簲鎶涘紓甯?
        assertNull(request.getServerName());
        assertNull(request.getRemoteAddr());
        assertNull(request.getRemoteHost());
        assertNull(request.getLocalName());
        assertNull(request.getLocalAddr());
        assertNull(request.getPathInfo());
        assertNull(request.getPathTranslated());
        assertNull(request.getQueryString());
        assertNull(request.getRemoteUser());
        assertNull(request.getUserPrincipal());
        assertNull(request.getRequestedSessionId());
    }

    @Test
    void servlet6IdentityMethods_returnRequestId() {
        String requestId = request.getRequestId();
        assertNotNull(requestId);
        assertEquals(requestId, request.getProtocolRequestId());
        assertFalse(requestId.isEmpty());
    }

    @Test
    void servlet6IdentityMethods_haveUniqueIds() {
        AbstractFastFailHttpServletRequest other = new AbstractFastFailHttpServletRequest() {};
        assertNotEquals(request.getRequestId(), other.getRequestId());
    }

    @Test
    void getServletConnection_reportsConnectionInfo() {
        javax.servlet.ServletConnection connection = request.getServletConnection();
        assertNotNull(connection);
        assertNotNull(connection.getConnectionId());
        assertEquals("HTTP/1.1", connection.getProtocol());
        assertNotNull(connection.getProtocolConnectionId());
        assertFalse(connection.isSecure());
    }

    @Test
    void sessionMethods_fastFail() {
        assertThrows(UnsupportedOperationException.class, () -> request.changeSessionId());
        assertThrows(UnsupportedOperationException.class, () -> request.isRequestedSessionIdValid());
        assertThrows(UnsupportedOperationException.class, () -> request.isRequestedSessionIdFromCookie());
        assertThrows(UnsupportedOperationException.class, () -> request.isRequestedSessionIdFromURL());
    }

    @Test
    void authenticationMethods_fastFail() {
        assertThrows(UnsupportedOperationException.class, () -> request.login("u", "p"));
        assertThrows(UnsupportedOperationException.class, () -> request.logout());
        assertThrows(UnsupportedOperationException.class, () -> request.getPart("p"));
        assertThrows(UnsupportedOperationException.class, () -> request.upgrade(javax.servlet.http.HttpUpgradeHandler.class));
    }
}

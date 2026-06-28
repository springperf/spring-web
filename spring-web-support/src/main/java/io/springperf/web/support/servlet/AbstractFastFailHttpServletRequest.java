package io.springperf.web.support.servlet;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.*;

public abstract class AbstractFastFailHttpServletRequest
        implements HttpServletRequest {

    protected static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException(
                method + " is not supported: not running in a Servlet container"
        );
    }

    // ================= ServletRequest =================

    @Override
    public Object getAttribute(String name) {
        throw unsupported("getAttribute");
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        throw unsupported("getAttributeNames");
    }

    @Override
    public String getCharacterEncoding() {
        return StandardCharsets.UTF_8.name();
    }

    @Override
    public void setCharacterEncoding(String env) {
        throw unsupported("setCharacterEncoding");
    }

    @Override
    public int getContentLength() {
        return -1;
    }

    @Override
    public long getContentLengthLong() {
        return -1L;
    }

    @Override
    public String getContentType() {
        return null;
    }

    @Override
    public ServletInputStream getInputStream() {
        throw unsupported("getInputStream");
    }

    @Override
    public String getParameter(String name) {
        return null;
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return Collections.emptyMap();
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.emptyEnumeration();
    }

    @Override
    public String[] getParameterValues(String name) {
        return null;
    }

    @Override
    public String getProtocol() {
        return "HTTP/1.1";
    }

    @Override
    public String getScheme() {
        return "http";
    }

    @Override
    public String getServerName() {
        return null;
    }

    @Override
    public int getServerPort() {
        return -1;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        throw unsupported("getReader");
    }

    @Override
    public String getRemoteAddr() {
        return null;
    }

    @Override
    public String getRemoteHost() {
        return null;
    }

    @Override
    public void setAttribute(String name, Object o) {
        throw unsupported("setAttribute");
    }

    @Override
    public void removeAttribute(String name) {
        throw unsupported("removeAttribute");
    }

    @Override
    public Locale getLocale() {
        return Locale.getDefault();
    }

    @Override
    public Enumeration<Locale> getLocales() {
        return Collections.enumeration(Arrays.asList(getLocale()));
    }

    @Override
    public boolean isSecure() {
        return false;
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        throw unsupported("getRequestDispatcher");
    }

    // getRealPath(String) removed in Servlet 6.0

    @Override
    public int getRemotePort() {
        return -1;
    }

    @Override
    public String getLocalName() {
        return null;
    }

    @Override
    public String getLocalAddr() {
        return null;
    }

    @Override
    public int getLocalPort() {
        return -1;
    }

    @Override
    public ServletContext getServletContext() {
        throw unsupported("getServletContext");
    }

    @Override
    public AsyncContext startAsync() {
        throw unsupported("startAsync");
    }

    @Override
    public AsyncContext startAsync(
            ServletRequest servletRequest,
            ServletResponse servletResponse) {
        throw unsupported("startAsync");
    }

    @Override
    public boolean isAsyncStarted() {
        return false;
    }

    @Override
    public boolean isAsyncSupported() {
        return false;
    }

    @Override
    public AsyncContext getAsyncContext() {
        throw unsupported("getAsyncContext");
    }

    @Override
    public DispatcherType getDispatcherType() {
        return DispatcherType.REQUEST;
    }

    @Override
    public String getProtocolRequestId() {
        return null;
    }

    @Override
    public String getRequestId() {
        return null;
    }

    @Override
    public ServletConnection getServletConnection() {
        throw unsupported("getServletConnection");
    }

    // ================= HttpServletRequest =================

    @Override
    public String getAuthType() {
        return null;
    }

    @Override
    public Cookie[] getCookies() {
        return new Cookie[0];
    }

    @Override
    public long getDateHeader(String name) {
        return -1;
    }

    @Override
    public String getHeader(String name) {
        return null;
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return Collections.emptyEnumeration();
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return Collections.emptyEnumeration();
    }

    @Override
    public int getIntHeader(String name) {
        return -1;
    }

    @Override
    public String getMethod() {
        throw unsupported("getMethod");
    }

    @Override
    public String getPathInfo() {
        return null;
    }

    @Override
    public String getPathTranslated() {
        return null;
    }

    @Override
    public String getContextPath() {
        return "";
    }

    @Override
    public String getQueryString() {
        return null;
    }

    @Override
    public String getRemoteUser() {
        return null;
    }

    @Override
    public boolean isUserInRole(String role) {
        return false;
    }

    @Override
    public Principal getUserPrincipal() {
        return null;
    }

    @Override
    public String getRequestedSessionId() {
        return null;
    }

    @Override
    public String getRequestURI() {
        throw unsupported("getRequestURI");
    }

    @Override
    public StringBuffer getRequestURL() {
        throw unsupported("getRequestURL");
    }

    @Override
    public String getServletPath() {
        return "";
    }

    @Override
    public HttpSession getSession(boolean create) {
        throw unsupported("getSession");
    }

    @Override
    public HttpSession getSession() {
        throw unsupported("getSession");
    }

    @Override
    public String changeSessionId() {
        throw unsupported("getSession");
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        throw unsupported("getSession");
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        throw unsupported("getSession");
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        throw unsupported("getSession");
    }

    // isRequestedSessionIdFromUrl() removed in Servlet 6.0

    @Override
    public boolean authenticate(HttpServletResponse httpServletResponse) throws IOException, ServletException {
        throw unsupported("getSession");
    }

    @Override
    public void login(String s, String s1) throws ServletException {
        throw unsupported("getSession");
    }

    @Override
    public void logout() throws ServletException {
        throw unsupported("getSession");
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        throw unsupported("getSession");
    }

    @Override
    public Part getPart(String s) throws IOException, ServletException {
        throw unsupported("getSession");
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> aClass) throws IOException, ServletException {
        throw unsupported("getSession");
    }
}


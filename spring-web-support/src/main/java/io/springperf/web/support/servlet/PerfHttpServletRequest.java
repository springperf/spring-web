package io.springperf.web.support.servlet;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.springperf.web.core.async.AsyncSupportUtils;
import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.http.support.HttpInputMessagePart;
import io.springperf.web.support.servlet.session.PerfHttpSession;
import io.springperf.web.support.servlet.session.PerfHttpSessionManager;
import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.ReadListener;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.WebConnection;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

public class PerfHttpServletRequest extends AbstractFastFailHttpServletRequest {

    private WebServerHttpRequest request;
    private volatile Cookie[] cookies;
    private DispatcherType dispatcherType = DispatcherType.REQUEST;
    private volatile boolean calledInputStream;
    private volatile boolean calledReader;

    public PerfHttpServletRequest(WebServerHttpRequest request) {
        this.request = request;
    }

    /**
     * 重新绑定底层的 {@link WebServerHttpRequest} 委托对象。
     * 当 WebFilter 包装了请求后调用，使此 {@code PerfHttpServletRequest}
     * 后续操作指向包装后的请求，而非创建新实例。
     */
    /**
     * 供子类（如 {@link NettyHttpServletRequest}）访问底层委托对象。
     */
    protected WebServerHttpRequest getDelegateRequest() {
        return request;
    }

    /**
     * 供子类访问底层响应委托对象。
     */
    protected WebServerHttpResponse getDelegateResponse() {
        HttpServletResponse resp = ServletAttribute.getResponse(request.getRequestContext());
        if (resp instanceof PerfHttpServletResponse) {
            return ((PerfHttpServletResponse) resp).getResponse();
        }
        return null;
    }

    public void rebind(WebServerHttpRequest request) {
        if (this.request != request) {
            this.request = request;
            this.cookies = null;
        }
    }

    @Override public String getMethod() { return request.getMethodValue(); }
    @Override public String getRequestURI() { return request.getUriStr(); }

    @Override
    public StringBuffer getRequestURL() {
        StringBuffer sb = new StringBuffer();
        sb.append(getScheme()).append("://");
        sb.append(getServerName());
        int port = getServerPort();
        String scheme = getScheme();
        if ((!"http".equals(scheme) || port != 80) && (!"https".equals(scheme) || port != 443)) {
            sb.append(':').append(port);
        }
        sb.append(getRequestURI());
        return sb;
    }

    @Override
    public String getQueryString() {
        String uri = request.getUriStrWithQuery();
        int idx = uri.indexOf('?');
        return idx >= 0 ? uri.substring(idx + 1) : null;
    }

    @Override public String getPathInfo() { return request.getPath(); }
    @Override public String getContextPath() { return request.getWebContext().getContextPath(); }
    @Override public String getHeader(String name) { return request.getHeaders().getFirst(name); }
    @Override public Enumeration<String> getHeaders(String name) { return Collections.enumeration(request.getHeaders().get(name)); }
    @Override public Enumeration<String> getHeaderNames() { return Collections.enumeration(request.getHeaders().keySet()); }

    @Override
    public long getDateHeader(String name) {
        String value = getHeader(name);
        return value != null ? HttpHeaderDateParser.parseDate(value) : -1;
    }

    @Override public int getIntHeader(String name) { String v = getHeader(name); return v != null ? Integer.parseInt(v) : -1; }
    @Override public String getContentType() { return request.getHeaders().getFirst(HttpHeaders.Names.CONTENT_TYPE); }
    @Override public int getContentLength() { return request.getContentLength(); }
    @Override public long getContentLengthLong() { return request.getContentLength(); }

    @Override
    public ServletInputStream getInputStream() {
        if (calledReader) {
            throw new IllegalStateException("getReader() has already been called");
        }
        calledInputStream = true;
        return createInputStream();
    }

    private NettyServletInputStream createInputStream() {
        try { return new NettyServletInputStream(request.getBody()); } catch (IOException e) { throw new RuntimeException(e); }
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (calledInputStream) {
            throw new IllegalStateException("getInputStream() has already been called");
        }
        calledReader = true;
        // 请求无 charset 时回退框架默认 UTF-8（与 BaseWebServerHttpRequest 一致）。
        // 修复前 getCharacterEncoding() 返回 null → InputStreamReader(stream, null) → IllegalArgumentException。
        String encoding = getCharacterEncoding();
        return new BufferedReader(new InputStreamReader(createInputStream(), encoding != null ? encoding : StandardCharsets.UTF_8.name()));
    }
    @Override public String getCharacterEncoding() { return request.getCharacterEncoding() == null ? null : request.getCharacterEncoding().name(); }
    @Override public void setCharacterEncoding(String env) { request.setCharacterEncoding(Charset.forName(env)); }
    @Override public Locale getLocale() { return request.getLocales().get(0); }
    @Override public Enumeration<Locale> getLocales() { return Collections.enumeration(request.getLocales()); }
    @Override public String getParameter(String name) { return request.getParameter(name); }
    @Override public Map<String, String[]> getParameterMap() { return request.getParameterMapArray(); }
    @Override public Enumeration<String> getParameterNames() { return Collections.enumeration(request.getParameterMap().keySet()); }
    @Override public String[] getParameterValues(String name) { return request.getParameterValues(name); }
    @Override public Object getAttribute(String name) { return request.getRequestContext().getAttribute(name); }
    @Override public Enumeration<String> getAttributeNames() { return Collections.enumeration(request.getRequestContext().getAttributes().keySet()); }
    @Override public void setAttribute(String name, Object o) { request.getRequestContext().setAttribute(name, o); }
    @Override public void removeAttribute(String name) { request.getRequestContext().removeAttribute(name); }
    @Override public int getServerPort() { return request.getWebContext().getProps().getInt(io.springperf.web.context.PropertiesConstant.SERVER_PORT); }
    @Override public String getScheme() { return "http"; }
    @Override public String getServerName() {
        String host = getHeader("Host");
        if (host == null) {
            return "localhost";
        }
        int colonIdx = host.lastIndexOf(':');
        int bracketIdx = host.lastIndexOf(']');
        if (colonIdx > bracketIdx) {
            return host.substring(0, colonIdx);
        }
        return host;
    }
    @Override public String getLocalAddr() { return "127.0.0.1"; }
    @Override public String getRemoteAddr() { return "127.0.0.1"; }
    @Override public String getRemoteHost() { return getRemoteAddr(); }

    @Override
    public DispatcherType getDispatcherType() {
        return dispatcherType;
    }

    public void setDispatcherType(DispatcherType dispatcherType) {
        this.dispatcherType = dispatcherType;
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        if (path == null) {
            return null;
        }
        return new PerfRequestDispatcher(path);
    }

    @Override
    public ServletContext getServletContext() {
        io.springperf.web.support.servlet.context.PerfServletContext ctx =
                request.getWebContext().getWebComponent(io.springperf.web.support.servlet.context.PerfServletContext.class);
        if (ctx != null) {
            return ctx;
        }
        return super.getServletContext();
    }

    // ===================== Multipart (fallback) =====================

    @Override
    public Collection<javax.servlet.http.Part> getParts() throws IOException, ServletException {
        org.springframework.util.MultiValueMap<String, HttpInputMessagePart> partMap = request.getPartMap();
        if (partMap == null) {
            throw new ServletException("Not a multipart request");
        }
        List<javax.servlet.http.Part> result = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, List<HttpInputMessagePart>> entry : partMap.entrySet()) {
            for (HttpInputMessagePart part : entry.getValue()) {
                result.add(new ServletPartAdapter(part));
            }
        }
        return result;
    }

    @Override
    public javax.servlet.http.Part getPart(String name) throws IOException, ServletException {
        org.springframework.util.MultiValueMap<String, HttpInputMessagePart> partMap = request.getPartMap();
        if (partMap == null) {
            throw new ServletException("Not a multipart request");
        }
        List<HttpInputMessagePart> parts = partMap.get(name);
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        return new ServletPartAdapter(parts.get(0));
    }

    // ===================== Async =====================

    @Override
    public AsyncContext startAsync() throws IllegalStateException {
        PerfAsyncContext existing = PerfAsyncContext.get(request.getRequestContext());
        if (existing != null) {
            return existing;
        }
        WebServerHttpResponse webResponse = getDelegateResponse();
        if (webResponse == null) {
            throw new IllegalStateException("Cannot start async: no WebServerHttpResponse available");
        }
        PerfAsyncWebRequest asyncWebRequest =
                (PerfAsyncWebRequest) AsyncSupportUtils.getAsyncWebRequest(request, webResponse);
        asyncWebRequest.startAsync();
        PerfAsyncContext asyncContext = new PerfAsyncContext(asyncWebRequest,
                request, webResponse, this, ServletAttribute.getResponse(request.getRequestContext()));
        PerfAsyncContext.set(request.getRequestContext(), asyncContext);
        return asyncContext;
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse)
            throws IllegalStateException {
        PerfAsyncContext existing = PerfAsyncContext.get(request.getRequestContext());
        if (existing != null) {
            return existing;
        }
        WebServerHttpResponse webResponse = getDelegateResponse();
        if (webResponse == null) {
            throw new IllegalStateException("Cannot start async: no WebServerHttpResponse available");
        }
        PerfAsyncWebRequest asyncWebRequest =
                (PerfAsyncWebRequest) AsyncSupportUtils.getAsyncWebRequest(request, webResponse);
        asyncWebRequest.startAsync();
        PerfAsyncContext asyncContext = new PerfAsyncContext(asyncWebRequest,
                request, webResponse, servletRequest, servletResponse);
        PerfAsyncContext.set(request.getRequestContext(), asyncContext);
        return asyncContext;
    }

    @Override
    public boolean isAsyncStarted() {
        PerfAsyncContext ctx = PerfAsyncContext.get(request.getRequestContext());
        return ctx != null;
    }

    @Override
    public boolean isAsyncSupported() {
        return true;
    }

    @Override
    public AsyncContext getAsyncContext() {
        PerfAsyncContext ctx = PerfAsyncContext.get(request.getRequestContext());
        if (ctx == null) {
            throw new IllegalStateException("Async not started");
        }
        return ctx;
    }

    // ===================== Upgrade =====================

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException {
        try {
            T handler = handlerClass.getDeclaredConstructor().newInstance();
            WebServerHttpResponse webResponse = getDelegateResponse();
            if (webResponse != null) {
                webResponse.setStatusCode(org.springframework.http.HttpStatus.SWITCHING_PROTOCOLS);
                webResponse.getHeaders().set(io.netty.handler.codec.http.HttpHeaderNames.CONNECTION.toString(),
                        io.netty.handler.codec.http.HttpHeaderValues.UPGRADE.toString());
                webResponse.getHeaders().set(io.netty.handler.codec.http.HttpHeaderNames.UPGRADE.toString(), "websocket");
                webResponse.setHandled();
            }
            HttpServletResponse servletResp = ServletAttribute.getResponse(request.getRequestContext());
            PerfWebConnection connection = new PerfWebConnection(
                    getInputStream(),
                    servletResp != null ? servletResp.getOutputStream() : null);
            Thread handlerThread = new Thread(() -> {
                try {
                    handler.init(connection);
                } catch (Exception e) {
                    throw new RuntimeException("HttpUpgradeHandler.init failed", e);
                }
            }, "upgrade-handler-" + handlerClass.getSimpleName());
            handlerThread.setDaemon(true);
            handlerThread.start();
            return handler;
        } catch (Exception e) {
            throw new ServletException("Failed to create HttpUpgradeHandler: " + handlerClass, e);
        }
    }

    // ===================== Cookies =====================

    @Override
    public Cookie[] getCookies() {
        Cookie[] result = cookies;
        if (result != null) {
            return result;
        }
        String cookieHeader = request.getHeaders().getFirst(HttpHeaders.Names.COOKIE);
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            cookies = new Cookie[0];
            return cookies;
        }
        Set<io.netty.handler.codec.http.cookie.Cookie> decoded = ServerCookieDecoder.STRICT.decode(cookieHeader);
        result = new Cookie[decoded.size()];
        int i = 0;
        for (io.netty.handler.codec.http.cookie.Cookie c : decoded) {
            result[i++] = new Cookie(c.name(), c.value());
        }
        cookies = result;
        return result;
    }

    // ===================== Session =====================

    @Override
    public String getRequestedSessionId() {
        // Check if a session was created during this request first
        PerfHttpSession cached = getCachedSession();
        if (cached != null) {
            return cached.getId();
        }
        // Fall back to session cookie
        String cookieName = DEFAULT_SESSION_COOKIE_NAME;
        PerfHttpSessionManager manager = getSessionManager();
        if (manager != null) {
            cookieName = manager.getCookieName();
        }
        Cookie[] allCookies = getCookies();
        if (allCookies != null) {
            for (Cookie cookie : allCookies) {
                if (cookieName.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    @Override
    public HttpSession getSession(boolean create) {
        // Check cached session in request attributes
        PerfHttpSession cached = getCachedSession();
        if (cached != null && !cached.isInvalid()) {
            return cached;
        }

        PerfHttpSessionManager manager = getSessionManager();
        if (manager == null) {
            throw new IllegalStateException("PerfHttpSessionManager not registered in WebContext");
        }

        // Try to get existing session from session ID
        String sessionId = getRequestedSessionId();
        if (sessionId != null) {
            PerfHttpSession session = manager.getSession(sessionId);
            if (session != null && !session.isInvalid()) {
                request.getRequestContext().setAttribute(PerfHttpSessionManager.SESSION_ATTR_KEY, session);
                return session;
            }
        }

        if (!create) {
            return null;
        }

        // Create new session
        PerfHttpSession newSession = manager.createSession();
        request.getRequestContext().setAttribute(PerfHttpSessionManager.SESSION_ATTR_KEY, newSession);
        setSessionCookie(newSession);
        return newSession;
    }

    @Override
    public HttpSession getSession() {
        return getSession(true);
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        return getRequestedSessionId() != null;
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        // Use cached session if available — avoids redundant storage lookup
        PerfHttpSession cached = getCachedSession();
        if (cached != null) {
            return !cached.isInvalid();
        }
        String sessionId = getRequestedSessionId();
        if (sessionId == null) {
            return false;
        }
        PerfHttpSessionManager manager = getSessionManager();
        if (manager == null) {
            return false;
        }
        PerfHttpSession session = manager.getSession(sessionId);
        if (session != null && !session.isInvalid()) {
            // Cache found session for subsequent getSession() calls
            request.getRequestContext().setAttribute(PerfHttpSessionManager.SESSION_ATTR_KEY, session);
            return true;
        }
        return false;
    }

    @Override
    public String changeSessionId() {
        PerfHttpSession session = (PerfHttpSession) getSession(false);
        if (session == null) {
            throw new IllegalStateException("changeSessionId failed: no session associated with this request");
        }
        PerfHttpSessionManager manager = getSessionManager();
        PerfHttpSession newSession = manager.changeSessionId(session);
        request.getRequestContext().setAttribute(PerfHttpSessionManager.SESSION_ATTR_KEY, newSession);
        setSessionCookie(newSession);
        return newSession.getId();
    }

    private void setSessionCookie(PerfHttpSession session) {
        HttpServletResponse resp = ServletAttribute.getResponse(request.getRequestContext());
        if (resp == null) {
            return;
        }
        PerfHttpSessionManager manager = getSessionManager();
        if (manager == null) {
            return;
        }
        Cookie sessionCookie = new Cookie(manager.getCookieName(), session.getId());
        sessionCookie.setPath(manager.getCookiePath());
        sessionCookie.setHttpOnly(true);
        boolean secure = manager.isCookieSecure();
        if (!secure) {
            String forwardedProto = request.getHeaders().getFirst("X-Forwarded-Proto");
            secure = "https".equalsIgnoreCase(forwardedProto);
        }
        sessionCookie.setSecure(secure);
        if (manager.getSameSite() != null && resp instanceof PerfHttpServletResponse) {
            ((PerfHttpServletResponse) resp).setSameSite(manager.getSameSite());
        }
        resp.addCookie(sessionCookie);
    }

    private static final String DEFAULT_SESSION_COOKIE_NAME = PerfHttpSessionManager.DEFAULT_SESSION_COOKIE_NAME;

    private PerfHttpSession getCachedSession() {
        return request.getRequestContext().getAttribute(PerfHttpSessionManager.SESSION_ATTR_KEY);
    }

    protected PerfHttpSessionManager getSessionManager() {
        return request.getWebContext().getWebComponent(PerfHttpSessionManager.class);
    }

    // ===================== Security =====================

    private PerfHttpPrincipal getPrincipalFromSession() {
        HttpSession session = getSession(false);
        if (session == null) {
            return null;
        }
        return (PerfHttpPrincipal) session.getAttribute(PerfHttpSessionManager.PRINCIPAL_KEY);
    }

    @Override
    public String getRemoteUser() {
        PerfHttpPrincipal principal = getPrincipalFromSession();
        return principal != null ? principal.getName() : null;
    }

    @Override
    public Principal getUserPrincipal() {
        return getPrincipalFromSession();
    }

    @Override
    public boolean isUserInRole(String role) {
        if (role == null) {
            return false;
        }
        PerfHttpPrincipal principal = getPrincipalFromSession();
        return principal != null && principal.hasRole(role);
    }

    @Override
    public void login(String username, String password) throws ServletException {
        PerfHttpSessionManager manager = getSessionManager();
        if (manager == null) {
            throw new ServletException("PerfHttpSessionManager not registered in WebContext");
        }
        Authenticator authenticator = manager.getAuthenticator();
        if (authenticator == null) {
            throw new ServletException("No Authenticator registered: define an Authenticator bean to enable login()");
        }
        Principal principal = authenticator.authenticate(username, password);
        if (principal == null) {
            throw new ServletException("Login failed for user: " + username);
        }
        PerfHttpPrincipal perfPrincipal = (principal instanceof PerfHttpPrincipal)
                ? (PerfHttpPrincipal) principal
                : new PerfHttpPrincipal(principal.getName());
        HttpSession session = getSession(true);
        session.setAttribute(PerfHttpSessionManager.PRINCIPAL_KEY, perfPrincipal);
    }

    @Override
    public void logout() throws ServletException {
        PerfHttpSessionManager manager = getSessionManager();
        if (manager == null) {
            return;
        }
        HttpSession session = getSession(false);
        if (session != null) {
            session.removeAttribute(PerfHttpSessionManager.PRINCIPAL_KEY);
        }
    }

    @Override
    public boolean authenticate(HttpServletResponse response) throws IOException {
        if (getPrincipalFromSession() != null) {
            return true;
        }
        response.setHeader("WWW-Authenticate", "Basic realm=\"spring-perf-web\"");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        return false;
    }

    // ===================== InputStream =====================

    static class NettyServletInputStream extends ServletInputStream {
        private final InputStream in;
        NettyServletInputStream(InputStream in) { this.in = in; }
        @Override public boolean isFinished() { try { return in.available() <= 0; } catch (IOException e) { return true; } }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener readListener) { throw new UnsupportedOperationException("Non-blocking IO is not supported"); }
        @Override public int read() throws IOException { return in.read(); }
    }

    static class HttpHeaderDateParser {
        private static final DateTimeFormatter RFC_1123_FORMAT = DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US).withZone(ZoneId.of("GMT"));
        private static final DateTimeFormatter RFC_1036_FORMAT = DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US).withZone(ZoneId.of("GMT"));
        private static final DateTimeFormatter ASCTIME_FORMAT = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.US).withZone(ZoneId.of("GMT"));
        private static final DateTimeFormatter[] FORMATS = {RFC_1123_FORMAT, RFC_1036_FORMAT, ASCTIME_FORMAT};

        public static long parseDate(String value) {
            if (value == null) return -1L;
            for (DateTimeFormatter formatter : FORMATS) {
                try { return Instant.from(formatter.parse(value)).toEpochMilli(); } catch (DateTimeParseException ignored) { }
            }
            return -1L;
        }
    }
}
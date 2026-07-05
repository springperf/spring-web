package io.springperf.web.support.servlet;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.support.servlet.session.PerfHttpSession;
import io.springperf.web.support.servlet.session.PerfHttpSessionManager;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

public class PerfHttpServletRequest extends AbstractFastFailHttpServletRequest {

    private WebServerHttpRequest request;
    private volatile Cookie[] cookies;

    public PerfHttpServletRequest(WebServerHttpRequest request) {
        this.request = request;
    }

    /**
     * 重新绑定底层的 {@link WebServerHttpRequest} 委托对象。
     * 当 WebFilter 包装了请求后调用，使此 {@code PerfHttpServletRequest}
     * 后续操作指向包装后的请求，而非创建新实例。
     */
    public void rebind(WebServerHttpRequest request) {
        if (this.request != request) {
            this.request = request;
            this.cookies = null;
        }
    }

    @Override public String getMethod() { return request.getMethodValue(); }
    @Override public String getRequestURI() { return request.getUriStr(); }

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
        try { return new NettyServletInputStream(request.getBody()); } catch (IOException e) { throw new RuntimeException(e); }
    }

    @Override public BufferedReader getReader() throws IOException { return new BufferedReader(new InputStreamReader(getInputStream(), getCharacterEncoding())); }
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

    private PerfHttpSessionManager getSessionManager() {
        return request.getWebContext().getWebComponent(PerfHttpSessionManager.class);
    }

    // ===================== InputStream =====================

    static class NettyServletInputStream extends ServletInputStream {
        private final InputStream in;
        NettyServletInputStream(InputStream in) { this.in = in; }
        @Override public boolean isFinished() { try { return in.available() <= 0; } catch (IOException e) { return true; } }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener readListener) { throw AbstractFastFailHttpServletRequest.unsupported("setReadListener"); }
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
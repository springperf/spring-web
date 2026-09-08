package io.springperf.web.support.servlet;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import javax.servlet.http.Cookie;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PerfHttpServletResponse extends AbstractFastFailHttpServletResponse {

    private WebServerHttpResponse response;
    private volatile String sameSite;
    private ServletAdapterContext adapterContext;
    private Locale locale;
    private volatile boolean calledOutputStream;
    private volatile boolean calledWriter;
    private PrintWriter cachedWriter;

    private static final Pattern CHARSET_PATTERN =
            Pattern.compile(";\\s*charset\\s*=\\s*([^;\\s]+)", Pattern.CASE_INSENSITIVE);

    public PerfHttpServletResponse(WebServerHttpResponse response) {
        this.response = response;
    }

    public void setAdapterContext(ServletAdapterContext adapterContext) {
        this.adapterContext = adapterContext;
    }

    /**
     * 重新绑定底层的 {@link WebServerHttpResponse} 委托对象。
     * 当 WebFilter 包装了响应后调用。
     */
    public WebServerHttpResponse getResponse() {
        return response;
    }

    public void rebind(WebServerHttpResponse response) {
        if (this.response != response) {
            this.response = response;
            this.cachedWriter = null;
        }
    }

    /**
     * 设置 SameSite 属性，作用于后续所有通过 {@link #addCookie(Cookie)} 添加的 Cookie。
     * 值为 {@code "Lax"}、{@code "Strict"} 或 {@code "None"}。
     */
    public void setSameSite(String sameSite) {
        this.sameSite = sameSite;
    }

    @Override public void setStatus(int sc) { response.setStatusCode(HttpStatusCode.valueOf(sc)); }
    @Override public int getStatus() { return response.getStatus().value(); }
    @Override public boolean isCommitted() { return response.isCommitted(); }
    @Override public boolean containsHeader(String name) { return response.getHeaders().containsKey(name); }

    @Override
    public void setDateHeader(String name, long date) {
        setHeader(name, formatDate(date));
    }

    @Override
    public void addDateHeader(String name, long date) {
        addHeader(name, formatDate(date));
    }

    @Override
    public void setIntHeader(String name, int value) {
        setHeader(name, Integer.toString(value));
    }

    @Override
    public void addIntHeader(String name, int value) {
        addHeader(name, Integer.toString(value));
    }

    @Override
    public void sendRedirect(String location) {
        if (location == null) {
            throw new IllegalArgumentException("Redirect location must not be null");
        }
        if (response.isCommitted()) {
            throw new IllegalStateException("Cannot send redirect: response already committed");
        }
        String redirectLocation = location;
        if (location.startsWith("/")) {
            String ctxPath = response.getWebContext().getContextPath();
            if (ctxPath != null && !ctxPath.isEmpty() && !"/".equals(ctxPath)) {
                redirectLocation = ctxPath + location;
            } else {
                redirectLocation = location;
            }
            if (adapterContext != null) {
                HttpServletRequest req = adapterContext.getRequest();
                if (req != null) {
                    String scheme = req.getScheme();
                    String serverName = req.getServerName();
                    int port = req.getServerPort();
                    StringBuilder sb = new StringBuilder();
                    sb.append(scheme).append("://").append(serverName);
                    if ((!"http".equals(scheme) || port != 80) && (!"https".equals(scheme) || port != 443)) {
                        sb.append(':').append(port);
                    }
                    redirectLocation = sb.toString() + redirectLocation;
                }
            }
        }
        setStatus(HttpServletResponse.SC_FOUND);
        setHeader(HttpHeaders.Names.LOCATION, redirectLocation);
    }

    @Override
    public String encodeURL(String url) {
        return encodeSessionUrl(url);
    }

    @Override
    public String encodeRedirectURL(String url) {
        return encodeSessionUrl(url);
    }

    private String encodeSessionUrl(String url) {
        if (url == null || adapterContext == null) {
            return url;
        }
        HttpServletRequest request = adapterContext.getRequest();
        if (request == null) {
            return url;
        }
        if (request.isRequestedSessionIdFromCookie()) {
            return url;
        }
        String sessionId = request.getRequestedSessionId();
        if (sessionId == null) {
            return url;
        }
        if (url.contains(";jsessionid=")) {
            return url;
        }
        int fragmentIdx = url.indexOf('#');
        String beforeFragment = fragmentIdx >= 0 ? url.substring(0, fragmentIdx) : url;
        String afterFragment = fragmentIdx >= 0 ? url.substring(fragmentIdx) : "";
        int queryIdx = beforeFragment.indexOf('?');
        String path = queryIdx >= 0 ? beforeFragment.substring(0, queryIdx) : beforeFragment;
        String query = queryIdx >= 0 ? beforeFragment.substring(queryIdx) : "";
        return path + ";jsessionid=" + sessionId + query + afterFragment;
    }

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US).withZone(ZoneId.of("GMT"));

    private static String formatDate(long date) {
        return DATE_FORMAT.format(Instant.ofEpochMilli(date));
    }

    @Override
    public void addCookie(Cookie cookie) {
        DefaultCookie nettyCookie = new DefaultCookie(cookie.getName(), cookie.getValue() != null ? cookie.getValue() : "");
        if (cookie.getDomain() != null) {
            nettyCookie.setDomain(cookie.getDomain());
        }
        if (cookie.getPath() != null) {
            nettyCookie.setPath(cookie.getPath());
        }
        nettyCookie.setMaxAge(cookie.getMaxAge());
        nettyCookie.setSecure(cookie.getSecure());
        nettyCookie.setHttpOnly(cookie.isHttpOnly());
        if (sameSite != null) {
            nettyCookie.setSameSite(CookieHeaderNames.SameSite.valueOf(sameSite));
        }
        response.getHeaders().add(HttpHeaders.Names.SET_COOKIE, ServerCookieEncoder.STRICT.encode(nettyCookie));
    }
    @Override public void setHeader(String name, String value) { response.getHeaders().set(name, value); }
    @Override public void addHeader(String name, String value) { response.getHeaders().add(name, value); }
    @Override public String getHeader(String name) { return response.getHeaders().getFirst(name); }
    @Override public Collection<String> getHeaders(String name) { return response.getHeaders().get(name); }
    @Override public Collection<String> getHeaderNames() { return response.getHeaders().keySet(); }
    @Override
    public void setContentType(String type) {
        setHeader(HttpHeaders.Names.CONTENT_TYPE, type);
        if (type != null) {
            Matcher m = CHARSET_PATTERN.matcher(type);
            if (m.find()) {
                String charset = m.group(1);
                try {
                    setCharacterEncoding(charset);
                } catch (Exception ignored) {
                }
            }
        }
    }
    @Override public String getContentType() { return getHeader(HttpHeaders.Names.CONTENT_TYPE); }
    @Override public String getCharacterEncoding() { return response.getCharacterEncoding() == null ? null : response.getCharacterEncoding().name(); }
    @Override public void setCharacterEncoding(String charset) { response.setCharacterEncoding(Charset.forName(charset)); }

    @Override
    public void setContentLength(int len) {
        if (len >= 0) {
            setHeader(HttpHeaders.Names.CONTENT_LENGTH, Integer.toString(len));
        }
    }

    @Override
    public void setContentLengthLong(long len) {
        if (len >= 0) {
            setHeader(HttpHeaders.Names.CONTENT_LENGTH, Long.toString(len));
        }
    }

    @Override
    public void setLocale(Locale loc) {
        this.locale = loc;
        if (loc != null) {
            response.getHeaders().set(HttpHeaders.Names.CONTENT_LANGUAGE, loc.toLanguageTag());
        }
    }

    @Override
    public Locale getLocale() {
        return locale != null ? locale : Locale.getDefault();
    }

    @Override
    public ServletOutputStream getOutputStream() {
        if (calledWriter) {
            throw new IllegalStateException("getWriter() has already been called");
        }
        calledOutputStream = true;
        return new ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener writeListener) { throw new UnsupportedOperationException("Non-blocking IO is not supported"); }
            @Override public void write(int b) throws IOException { response.getBody().write(b); }
            @Override public void print(String s) throws IOException { response.getBody().write(s.getBytes(getCharacterEncoding())); }
            @Override public void write(byte[] b) throws IOException { response.getBody().write(b); }
            @Override public void write(byte[] b, int off, int len) throws IOException { response.getBody().write(b, off, len); }
            @Override public void flush() throws IOException { response.getBody().flush(); }
        };
    }

    @Override public PrintWriter getWriter() throws IOException {
        if (calledOutputStream) {
            throw new IllegalStateException("getOutputStream() has already been called");
        }
        calledWriter = true;
        if (cachedWriter == null) {
            cachedWriter = new PrintWriter(new OutputStreamWriter(response.getBody(), getCharacterEncoding()), true);
        }
        return cachedWriter;
    }
    @Override public void flushBuffer() {
        try {
            if (cachedWriter != null) {
                cachedWriter.flush();
            }
            response.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override public int getBufferSize() { return response.getBufferSize(); }
    @Override
    public void reset() {
        response.getHeaders().clear();
        response.setStatusCode(HttpStatus.OK);
        response.resetBuffer();
    }
    @Override public void resetBuffer() { response.resetBuffer(); }

    @Override
    public void sendError(int sc) {
        if (response.isCommitted()) {
            throw new IllegalStateException("Cannot send error: response already committed");
        }
        HttpStatus status = HttpStatus.resolve(sc);
        if (status != null) {
            response.sendError(status);
        } else {
            // 非标准状态码（如 499/599/507）：HttpStatus 无法表示，按原始码值写入
            response.sendError(HttpStatusCode.valueOf(sc), null);
        }
    }
    @Override
    public void sendError(int sc, String msg) {
        if (response.isCommitted()) {
            throw new IllegalStateException("Cannot send error: response already committed");
        }
        HttpStatus status = HttpStatus.resolve(sc);
        if (status != null) {
            response.sendError(status, msg);
        } else {
            response.sendError(HttpStatusCode.valueOf(sc), msg);
        }
    }
}
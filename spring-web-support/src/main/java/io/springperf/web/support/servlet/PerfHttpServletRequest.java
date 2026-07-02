package io.springperf.web.support.servlet;

import io.netty.handler.codec.http.HttpHeaders;
import io.springperf.web.http.WebServerHttpRequest;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;

public class PerfHttpServletRequest extends AbstractFastFailHttpServletRequest {

    private final WebServerHttpRequest request;

    public PerfHttpServletRequest(WebServerHttpRequest request) {
        this.request = request;
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
    @Override public String getServerName() { String host = getHeader("Host"); return host != null ? host : "localhost"; }
    @Override public String getLocalAddr() { return "127.0.0.1"; }
    @Override public String getRemoteAddr() { return "127.0.0.1"; }
    @Override public String getRemoteHost() { return getRemoteAddr(); }

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
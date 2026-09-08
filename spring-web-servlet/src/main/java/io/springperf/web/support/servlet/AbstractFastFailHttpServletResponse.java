package io.springperf.web.support.servlet;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;

public abstract class AbstractFastFailHttpServletResponse
        implements HttpServletResponse {

    protected static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException(
                method + " is not supported: not running in a Servlet container"
        );
    }

    // ================= ServletResponse =================

    @Override
    public String getCharacterEncoding() {
        return "UTF-8";
    }

    @Override
    public String getContentType() {
        return null;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        throw unsupported("getOutputStream");
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        throw unsupported("getWriter");
    }

    @Override
    public void setCharacterEncoding(String charset) {
        // ignore or fast-fail，二选一
    }

    @Override
    public void setContentLength(int len) {
        // ignore
    }

    @Override
    public void setContentLengthLong(long len) {
        // ignore
    }

    @Override
    public void setContentType(String type) {
        // ignore
    }

    @Override
    public void setBufferSize(int size) {
        // ignore
    }

    @Override
    public int getBufferSize() {
        return 0;
    }

    @Override
    public void flushBuffer() throws IOException {
        // ignore
    }

    @Override
    public void resetBuffer() {
        throw unsupported("resetBuffer");
    }

    @Override
    public boolean isCommitted() {
        return false;
    }

    @Override
    public void reset() {
        throw unsupported("reset");
    }

    @Override
    public void setLocale(Locale loc) {
        // ignore
    }

    @Override
    public Locale getLocale() {
        return Locale.getDefault();
    }

    // ================= HttpServletResponse =================

    @Override
    public void addCookie(Cookie cookie) {
        throw unsupported("addCookie");
    }

    @Override
    public boolean containsHeader(String name) {
        return false;
    }

    @Override
    public String encodeURL(String url) {
        return url;
    }

    @Override
    public String encodeRedirectURL(String url) {
        return url;
    }

    @Override
    public String encodeUrl(String url) {
        return url;
    }

    @Override
    public String encodeRedirectUrl(String url) {
        return url;
    }

    @Override
    public void sendError(int sc, String msg) {
        throw unsupported("sendError");
    }

    @Override
    public void sendError(int sc) {
        throw unsupported("sendError");
    }

    @Override
    public void sendRedirect(String location) {
        throw unsupported("sendRedirect");
    }

    @Override
    public void setDateHeader(String name, long date) {
        throw unsupported("setDateHeader");
    }

    @Override
    public void addDateHeader(String name, long date) {
        throw unsupported("addDateHeader");
    }

    @Override
    public void setHeader(String name, String value) {
        throw unsupported("setHeader");
    }

    @Override
    public void addHeader(String name, String value) {
        throw unsupported("addHeader");
    }

    @Override
    public void setIntHeader(String name, int value) {
        throw unsupported("setIntHeader");
    }

    @Override
    public void addIntHeader(String name, int value) {
        throw unsupported("addIntHeader");
    }

    @Override
    public void setStatus(int sc) {
        throw unsupported("setStatus");
    }

    @Override
    public void setStatus(int sc, String sm) {
        throw unsupported("setStatus");
    }

    @Override
    public int getStatus() {
        return HttpServletResponse.SC_OK;
    }

    @Override
    public String getHeader(String name) {
        return null;
    }

    @Override
    public Collection<String> getHeaders(String name) {
        return Collections.emptyList();
    }

    @Override
    public Collection<String> getHeaderNames() {
        return Collections.emptyList();
    }
}

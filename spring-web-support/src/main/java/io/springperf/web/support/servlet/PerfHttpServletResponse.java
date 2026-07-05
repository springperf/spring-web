package io.springperf.web.support.servlet;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.springperf.web.http.WebServerHttpResponse;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import org.springframework.http.HttpStatus;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.Collection;

public class PerfHttpServletResponse extends AbstractFastFailHttpServletResponse {

    private WebServerHttpResponse response;
    private volatile String sameSite;

    public PerfHttpServletResponse(WebServerHttpResponse response) {
        this.response = response;
    }

    /**
     * 重新绑定底层的 {@link WebServerHttpResponse} 委托对象。
     * 当 WebFilter 包装了响应后调用。
     */
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

    @Override public void setStatus(int sc) { response.setStatusCode(HttpStatus.valueOf(sc)); }
    @Override public int getStatus() { return response.getStatus().value(); }

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
    @Override public void setContentType(String type) { setHeader(HttpHeaders.Names.CONTENT_TYPE, type); }
    @Override public String getContentType() { return getHeader(HttpHeaders.Names.CONTENT_TYPE); }
    @Override public String getCharacterEncoding() { return response.getCharacterEncoding() == null ? null : response.getCharacterEncoding().name(); }
    @Override public void setCharacterEncoding(String charset) { response.setCharacterEncoding(Charset.forName(charset)); }

    @Override
    public ServletOutputStream getOutputStream() {
        return new ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener writeListener) { throw AbstractFastFailHttpServletResponse.unsupported("setWriteListener"); }
            @Override public void write(int b) throws IOException { response.getBody().write(b); }
            @Override public void print(String s) throws IOException { response.getBody().write(s.getBytes(getCharacterEncoding())); }
            @Override public void write(byte[] b) throws IOException { response.getBody().write(b); }
            @Override public void write(byte[] b, int off, int len) throws IOException { response.getBody().write(b, off, len); }
            @Override public void flush() throws IOException { response.getBody().flush(); }
        };
    }

    private PrintWriter cachedWriter;

    @Override public PrintWriter getWriter() throws IOException {
        if (cachedWriter == null) {
            cachedWriter = new PrintWriter(new OutputStreamWriter(response.getBody(), getCharacterEncoding()), true);
        }
        return cachedWriter;
    }
    @Override public void flushBuffer() { try { response.flush(); } catch (IOException e) { throw new RuntimeException(e); } }
    @Override public int getBufferSize() { return response.getBufferSize(); }
    @Override public void setBufferSize(int size) { }
    @Override public boolean isCommitted() { return false; }
    @Override public void reset() { response.resetBuffer(); }
    @Override public void resetBuffer() { response.resetBuffer(); }
    @Override public void sendError(int sc) { response.sendError(HttpStatus.valueOf(sc)); }
    @Override public void sendError(int sc, String msg) { response.sendError(HttpStatus.valueOf(sc), msg); }
}
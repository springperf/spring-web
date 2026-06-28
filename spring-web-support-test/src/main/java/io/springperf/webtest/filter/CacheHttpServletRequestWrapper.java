package io.springperf.webtest.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.http.MediaType;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Objects;

/**
 * Request 缓存 Wrapper
 *
 * @author jy
 * @since 2023-12-06 16:32
 */
public class CacheHttpServletRequestWrapper extends HttpServletRequestWrapper {

    /**
     * 原始请求
     */
    private final HttpServletRequest origin;

    /**
     * 缓存的内容
     */
    private byte[] body;

    public CacheHttpServletRequestWrapper(HttpServletRequest request) {
        super(request);
        this.origin = request;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(this.getInputStream()));
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (origin.getContentType() == null || !origin.getContentType().toLowerCase().startsWith(MediaType.APPLICATION_JSON_VALUE)) {
            // 非JSON格式的请求，返回默认
            return super.getInputStream();
        }
        if (Objects.isNull(body)) {
            this.body = origin.getInputStream().readAllBytes();
        }
        final ByteArrayInputStream inputStream = new ByteArrayInputStream(body);
        // 返回 ServletInputStream
        return new ServletInputStream() {

            @Override
            public int read() {
                return inputStream.read();
            }

            @Override
            public boolean isFinished() {
                return false;
            }

            @Override
            public boolean isReady() {
                return false;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
            }

            @Override
            public int available() {
                return body.length;
            }

        };
    }

    public HttpServletRequest getOrigin() {
        return origin;
    }

}

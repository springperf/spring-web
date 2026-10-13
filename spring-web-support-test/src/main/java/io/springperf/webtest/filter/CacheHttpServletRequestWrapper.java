package io.springperf.webtest.filter;

import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;
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
        String contentType = origin.getContentType();
        // 用 Locale.ROOT 小写化（而非无参 toLowerCase）：土耳其语 locale 下 'I' 会小写成 'ı'，
        // 使 "APPLICATION/JSON" 匹配不上，JSON 请求会被误判为非 JSON。
        if (contentType == null
                || !contentType.toLowerCase(Locale.ROOT).startsWith(MediaType.APPLICATION_JSON_VALUE)) {
            // 非JSON格式的请求，返回默认
            return super.getInputStream();
        }
        if (Objects.isNull(body)) {
            // JDK 8 无 InputStream#readAllBytes（JDK 9+）：用 Spring 的 StreamUtils 等价读全量
            // （原实现为 Hutool 的 ServletUtil.getBodyBytes，语义同为读全部字节）。
            this.body = StreamUtils.copyToByteArray(origin.getInputStream());
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

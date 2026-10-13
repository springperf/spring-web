package io.springperf.webtest.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CacheHttpServletRequestWrapper} 单元护栏。
 *
 * <p>该类原用 Hutool（{@code ServletUtil.getBodyBytes} / {@code CharSequenceUtil.startWithIgnoreCase}），
 * 已改写为纯 JDK + Spring 写法（JDK 8 无 {@code InputStream#readAllBytes}，改用
 * {@code StreamUtils.copyToByteArray}）。本测试固定改写后的等价语义：</p>
 * <ul>
 *   <li>JSON 请求体被缓存后可重复读取，且原始流只消费一次；</li>
 *   <li>带 charset 的 {@code application/json;charset=UTF-8} 同样命中缓存（前缀匹配，非等值匹配）；</li>
 *   <li>非 JSON 与 {@code null} 内容类型直通原始流（不得 NPE —— Hutool 版对 null 返回 false，
 *       与原实现同为「走 super」分支）。</li>
 * </ul>
 */
class CacheHttpServletRequestWrapperTest {

    private static ServletInputStream streamOf(String body) {
        ByteArrayInputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        return new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }

            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
            }
        };
    }

    private static String drain(ServletInputStream in) throws IOException {
        byte[] buf = new byte[64];
        int total = 0;
        int n;
        while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) {
            total += n;
        }
        return new String(buf, 0, total, StandardCharsets.UTF_8);
    }

    @Test
    void jsonRequest_bodyIsCachedAndReadableTwice() throws IOException {
        HttpServletRequest origin = mock(HttpServletRequest.class);
        when(origin.getContentType()).thenReturn(MediaType.APPLICATION_JSON_VALUE);
        when(origin.getInputStream()).thenReturn(streamOf("{\"a\":1}"));

        CacheHttpServletRequestWrapper wrapper = new CacheHttpServletRequestWrapper(origin);

        assertEquals("{\"a\":1}", drain(wrapper.getInputStream()));
        assertEquals("{\"a\":1}", drain(wrapper.getInputStream()));
        // 缓存生效：原始流只被消费一次
        verify(origin, times(1)).getInputStream();
    }

    /** 前缀匹配：{@code application/json;charset=UTF-8} 也必须命中缓存（原 Hutool startWithIgnoreCase 语义）。 */
    @Test
    void jsonRequest_contentTypeWithCharset_isCached() throws IOException {
        HttpServletRequest origin = mock(HttpServletRequest.class);
        when(origin.getContentType()).thenReturn(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        when(origin.getInputStream()).thenReturn(streamOf("{\"b\":2}"));

        CacheHttpServletRequestWrapper wrapper = new CacheHttpServletRequestWrapper(origin);

        assertEquals("{\"b\":2}", drain(wrapper.getInputStream()));
        verify(origin, times(1)).getInputStream();
    }

    /** 大小写不敏感：{@code APPLICATION/JSON} 仍应命中缓存（对齐 Hutool 的 startWithIgnoreCase）。 */
    @Test
    void jsonRequest_contentTypeCaseInsensitive_isCached() throws IOException {
        HttpServletRequest origin = mock(HttpServletRequest.class);
        when(origin.getContentType()).thenReturn("APPLICATION/JSON");
        when(origin.getInputStream()).thenReturn(streamOf("{}"));

        CacheHttpServletRequestWrapper wrapper = new CacheHttpServletRequestWrapper(origin);

        assertEquals("{}", drain(wrapper.getInputStream()));
        verify(origin, times(1)).getInputStream();
    }

    @Test
    void nonJsonRequest_delegatesToOriginStream() throws IOException {
        HttpServletRequest origin = mock(HttpServletRequest.class);
        when(origin.getContentType()).thenReturn(MediaType.TEXT_PLAIN_VALUE);
        ServletInputStream original = streamOf("plain");
        when(origin.getInputStream()).thenReturn(original);

        assertSame(original, new CacheHttpServletRequestWrapper(origin).getInputStream());
    }

    @Test
    void nullContentType_delegatesToOriginStreamWithoutNpe() throws IOException {
        HttpServletRequest origin = mock(HttpServletRequest.class);
        when(origin.getContentType()).thenReturn(null);
        ServletInputStream original = streamOf("no-ct");
        when(origin.getInputStream()).thenReturn(original);

        assertSame(original, new CacheHttpServletRequestWrapper(origin).getInputStream());
    }
}

package io.springperf.web.support.servlet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Enumeration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import io.springperf.web.context.WebContext;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;

/** 请求适配层的防御分支：空头枚举、日期格式、输入流 available 失败与静态工具方法。 */
class PerfHttpServletRequestEdgeTest {

    private WebServerHttpRequest request;
    private HttpHeaders headers;
    private PerfHttpServletRequest servletRequest;

    @BeforeEach
    void setUp() {
        request = mock(WebServerHttpRequest.class);
        headers = new HttpHeaders();
        when(request.getHeaders()).thenReturn(headers);
        when(request.getWebContext()).thenReturn(mock(WebContext.class));
        when(request.getRequestContext()).thenReturn(mock(RequestContext.class));
        servletRequest = new PerfHttpServletRequest(request);
    }

    @Test
    void getHeaders_unknownNameReturnsEmptyEnumeration() {
        Enumeration<String> values = servletRequest.getHeaders("Missing");

        assertNotNull(values);
        assertFalse(values.hasMoreElements(), "HttpHeaders.get 返回 null 时应给出空枚举而非 NPE");
    }

    @Test
    void getDateHeader_acceptsAllThreeHttpDateFormats() {
        headers.set("Date", "Thu, 01 Jan 1970 00:00:00 GMT");
        assertEquals(0L, servletRequest.getDateHeader("Date"));

        headers.set("Date", "Thu Jan 1 00:00:00 1970");
        assertEquals(0L, servletRequest.getDateHeader("Date"));

        // 两位年份按 HTTP 50 年滑动窗口解释：70 → 1970、94 → 1994、22 → 2022
        headers.set("Date", "Thursday, 01-Jan-70 00:00:00 GMT");
        assertEquals(0L, servletRequest.getDateHeader("Date"));
        headers.set("Date", "Sunday, 06-Nov-94 08:49:37 GMT");
        assertEquals(784111777000L, servletRequest.getDateHeader("Date"));
        headers.set("Date", "Thursday, 01-Dec-22 00:00:00 GMT");
        assertEquals(1669852800000L, servletRequest.getDateHeader("Date"));
    }

    @Test
    void httpHeaderDateParser_nullValueReturnsMinusOne() {
        assertEquals(-1L, PerfHttpServletRequest.HttpHeaderDateParser.parseDate(null));
    }

    @Test
    void nettyServletInputStream_isFinished_whenAvailableThrows() {
        InputStream broken = new InputStream() {
            @Override
            public int read() {
                return -1;
            }

            @Override
            public int available() throws IOException {
                throw new IOException("boom");
            }
        };

        assertTrue(new PerfHttpServletRequest.NettyServletInputStream(broken).isFinished(), "available() 失败时应保守地视为已结束");
    }

    @Test
    void nettyServletInputStream_isFinished_whenBytesRemain() {
        assertFalse(new PerfHttpServletRequest.NettyServletInputStream(new ByteArrayInputStream(new byte[] { 1 }))
                .isFinished());
    }

    @Test
    void webContextOf_nullRequestReturnsNull() throws Exception {
        Method method = PerfHttpServletRequest.class.getDeclaredMethod("webContextOf", WebServerHttpRequest.class);
        method.setAccessible(true);

        assertNull(method.invoke(null, new Object[] { null }));
    }

    @Test
    void isUserInRole_nullRoleShortCircuitsWithoutSessionLookup() {
        assertFalse(servletRequest.isUserInRole(null), "null 角色应在会话查找前短路");
        assertThrows(IllegalStateException.class, () -> servletRequest.isUserInRole("admin"),
                "无 PerfHttpSessionManager 时应显式失败，不得静默误判为无角色");
    }

    @Test
    void getCookies_emptyCookieHeaderReturnsNoCookies() {
        headers.set("Cookie", "");

        assertEquals(0, servletRequest.getCookies().length);
    }
}

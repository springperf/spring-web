package io.springperf.web.support.servlet;

import io.springperf.web.context.WebContext;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.support.HttpInputMessagePart;
import javax.servlet.http.Cookie;
import javax.servlet.http.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 验证 {@link PerfHttpServletRequest} 的 HTTP 细节逻辑：
 * 日期头解析、QueryString、Host 解析、Cookie 解析、multipart parts、reader/stream 互斥。
 */
class PerfHttpServletRequestDetailsTest {

    private WebServerHttpRequest request;
    private RequestContext requestContext;
    private WebContext webContext;

    @BeforeEach
    void setUp() {
        request = mock(WebServerHttpRequest.class);
        requestContext = mock(RequestContext.class);
        webContext = mock(WebContext.class);
        when(request.getRequestContext()).thenReturn(requestContext);
        when(request.getWebContext()).thenReturn(webContext);
        Map<RequestAttribute<?>, Object> fastAttrs = new HashMap<>();
        Map<String, Object> stringAttrs = new HashMap<>();
        when(requestContext.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> fastAttrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            fastAttrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(requestContext).setAttribute(any(RequestAttribute.class), any());
        when(requestContext.getAttribute(anyString())).thenAnswer(inv -> stringAttrs.get(inv.getArgument(0)));
        when(requestContext.getAttributes()).thenReturn(stringAttrs);
        doAnswer(inv -> {
            stringAttrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(requestContext).setAttribute(anyString(), any());
    }

    private void headers(String... kv) {
        HttpHeaders h = new HttpHeaders();
        for (int i = 0; i < kv.length; i += 2) {
            h.add(kv[i], kv[i + 1]);
        }
        when(request.getHeaders()).thenReturn(h);
    }

    @Test
    void getDateHeader_rfc1123_parses() {
        headers("Date", "Thu, 01 Jan 1970 00:00:00 GMT");
        PerfHttpServletRequest servletReq = new PerfHttpServletRequest(request);
        assertEquals(0L, servletReq.getDateHeader("Date"));
    }

    @Test
    void getDateHeader_invalid_returnsMinusOne() {
        headers("Date", "not-a-date");
        PerfHttpServletRequest servletReq = new PerfHttpServletRequest(request);
        assertEquals(-1L, servletReq.getDateHeader("Date"));
    }

    @Test
    void getDateHeader_missing_returnsMinusOne() {
        headers("Other", "x");
        PerfHttpServletRequest servletReq = new PerfHttpServletRequest(request);
        assertEquals(-1L, servletReq.getDateHeader("Date"));
    }

    @Test
    void getQueryString_extractsAfterQuestion() {
        when(request.getUriStrWithQuery()).thenReturn("/path?a=1&b=2");
        PerfHttpServletRequest servletReq = new PerfHttpServletRequest(request);
        assertEquals("a=1&b=2", servletReq.getQueryString());
    }

    @Test
    void getQueryString_noQuestion_returnsNull() {
        when(request.getUriStrWithQuery()).thenReturn("/path");
        PerfHttpServletRequest servletReq = new PerfHttpServletRequest(request);
        assertNull(servletReq.getQueryString());
    }

    @Test
    void getServerName_hostWithoutPort() {
        headers("Host", "example.com");
        PerfHttpServletRequest servletReq = new PerfHttpServletRequest(request);
        assertEquals("example.com", servletReq.getServerName());
    }

    @Test
    void getServerName_hostWithPort_stripsPort() {
        headers("Host", "example.com:8080");
        PerfHttpServletRequest servletReq = new PerfHttpServletRequest(request);
        assertEquals("example.com", servletReq.getServerName());
    }

    @Test
    void getServerName_noHost_returnsLocalhost() {
        headers("Other", "x");
        PerfHttpServletRequest servletReq = new PerfHttpServletRequest(request);
        assertEquals("localhost", servletReq.getServerName());
    }

    @Test
    void getCookies_parsesCookieHeader() {
        headers("Cookie", "session=abc123; theme=dark");
        PerfHttpServletRequest servletReq = new PerfHttpServletRequest(request);
        Cookie[] cookies = servletReq.getCookies();
        assertEquals(2, cookies.length);
        Map<String, String> map = new HashMap<>();
        for (Cookie c : cookies) {
            map.put(c.getName(), c.getValue());
        }
        assertEquals("abc123", map.get("session"));
        assertEquals("dark", map.get("theme"));
    }

    @Test
    void getCookies_noHeader_empty() {
        headers("Other", "x");
        PerfHttpServletRequest servletReq = new PerfHttpServletRequest(request);
        assertEquals(0, servletReq.getCookies().length);
    }

    @Test
    void getParts_and_getPart_adaptPartMap() throws Exception {
        HttpInputMessagePart part1 = mock(HttpInputMessagePart.class);
        when(part1.getName()).thenReturn("field1");
        MultiValueMap<String, HttpInputMessagePart> partMap = new LinkedMultiValueMap<>();
        partMap.add("field1", part1);
        when(request.getPartMap()).thenReturn(partMap);

        PerfHttpServletRequest servletReq = new PerfHttpServletRequest(request);
        Collection<Part> parts = servletReq.getParts();
        assertEquals(1, parts.size());
        Part p = servletReq.getPart("field1");
        assertNotNull(p);
        assertEquals("field1", p.getName());
    }

    @Test
    void getParts_notMultipart_throws() {
        when(request.getPartMap()).thenReturn(null);
        PerfHttpServletRequest servletReq = new PerfHttpServletRequest(request);
        assertThrows(javax.servlet.ServletException.class, servletReq::getParts);
    }

    @Test
    void getReader_and_getInputStream_areMutuallyExclusive() throws Exception {
        when(request.getBody()).thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(request.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        PerfHttpServletRequest servletReq = new PerfHttpServletRequest(request);

        BufferedReader reader = servletReq.getReader();
        assertEquals("hello", reader.readLine());
        assertThrows(IllegalStateException.class, servletReq::getInputStream,
                "getReader 后调用 getInputStream 应抛 IllegalStateException");
    }

    @Test
    void getInputStream_thenGetReader_throws() throws Exception {
        when(request.getBody()).thenReturn(new ByteArrayInputStream(new byte[0]));
        PerfHttpServletRequest servletReq = new PerfHttpServletRequest(request);
        servletReq.getInputStream();
        assertThrows(IllegalStateException.class, servletReq::getReader);
    }

    @Test
    void rebind_sameRequest_keepsCookies() throws Exception {
        headers("Cookie", "a=1");
        PerfHttpServletRequest servletReq = new PerfHttpServletRequest(request);
        servletReq.getCookies();
        servletReq.rebind(request); // same instance
        assertEquals(1, servletReq.getCookies().length);
    }
}
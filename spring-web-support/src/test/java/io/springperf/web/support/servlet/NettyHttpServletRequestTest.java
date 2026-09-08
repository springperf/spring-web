package io.springperf.web.support.servlet;

import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验证 {@link NettyHttpServletRequest} 的网络地址/协议/URL 构造逻辑。
 */
class NettyHttpServletRequestTest {

    private WebServerHttpRequest mockRequest(URI uri, InetSocketAddress remote, InetSocketAddress local) {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getRemoteAddress()).thenReturn(remote);
        when(req.getLocalAddress()).thenReturn(local);
        when(req.getURI()).thenReturn(uri);
        when(req.getHeaders()).thenReturn(new org.springframework.http.HttpHeaders());
        when(req.getPath()).thenReturn(uri.getPath());
        return req;
    }

    @Test
    void remote_returnsHostAndPort() {
        WebServerHttpRequest req = mockRequest(
                URI.create("http://localhost:8080/test"),
                new InetSocketAddress("192.168.1.10", 5000),
                new InetSocketAddress("127.0.0.1", 8080));
        NettyHttpServletRequest servletReq = new NettyHttpServletRequest(req);

        assertEquals("192.168.1.10", servletReq.getRemoteAddr());
        assertEquals("192.168.1.10", servletReq.getRemoteHost());
        assertEquals(5000, servletReq.getRemotePort());
    }

    @Test
    void local_returnsHostAndPort() {
        WebServerHttpRequest req = mockRequest(
                URI.create("http://localhost:8080/test"),
                new InetSocketAddress("192.168.1.10", 5000),
                new InetSocketAddress("127.0.0.1", 9090));
        NettyHttpServletRequest servletReq = new NettyHttpServletRequest(req);

        assertEquals("127.0.0.1", servletReq.getLocalAddr());
        assertEquals("127.0.0.1", servletReq.getLocalName());
        assertEquals(9090, servletReq.getLocalPort());
        assertEquals(9090, servletReq.getServerPort(), "server port 应取实际绑定端口而非配置值");
    }

    @Test
    void remoteAddressNull_fallsBackToSuper() {
        WebServerHttpRequest req = mockRequest(
                URI.create("http://localhost:8080/test"), null, null);
        NettyHttpServletRequest servletReq = new NettyHttpServletRequest(req);
        // 无 remoteAddress，走父类实现（不抛异常）
        assertNotNull(servletReq.getRemoteAddr());
        assertNotNull(servletReq.getLocalAddr());
    }

    @Test
    void scheme_and_isSecure() {
        WebServerHttpRequest httpReq = mockRequest(
                URI.create("http://localhost:8080/test"), null, null);
        assertFalse(new NettyHttpServletRequest(httpReq).isSecure());
        assertEquals("http", new NettyHttpServletRequest(httpReq).getScheme());

        WebServerHttpRequest httpsReq = mockRequest(
                URI.create("https://localhost:8443/test"), null, null);
        assertTrue(new NettyHttpServletRequest(httpsReq).isSecure());
        assertEquals("https", new NettyHttpServletRequest(httpsReq).getScheme());
    }

    @Test
    void getRequestURL_nonDefaultPort_includesPort() {
        WebServerHttpRequest req = mockRequest(
                URI.create("http://localhost:8080/test"),
                new InetSocketAddress("192.168.1.10", 5000),
                new InetSocketAddress("127.0.0.1", 8080));
        NettyHttpServletRequest servletReq = new NettyHttpServletRequest(req);
        // getRequestURI 来自父类（基于 request.getPath 等），此处验证 URL 含端口
        String url = servletReq.getRequestURL().toString();
        assertTrue(url.startsWith("http://"), "URL 应以 scheme 开头: " + url);
        assertTrue(url.contains(":8080"), "自定义端口应包含: " + url);
    }

    @Test
    void getRequestURL_httpPort80_omitsPort() {
        WebServerHttpRequest req = mockRequest(
                URI.create("http://localhost:80/test"),
                null,
                new InetSocketAddress("127.0.0.1", 80));
        NettyHttpServletRequest servletReq = new NettyHttpServletRequest(req);
        String url = servletReq.getRequestURL().toString();
        assertFalse(url.contains(":80"), "HTTP 默认端口 80 应省略: " + url);
    }
}
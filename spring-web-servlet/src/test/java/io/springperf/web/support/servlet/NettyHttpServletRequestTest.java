package io.springperf.web.support.servlet;

import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        // getRequestURI() 读的是 getUriStr()（不是 getPath()）：缺这个 stub 会让 URL 拼出 "null" 路径，
        // 而 startsWith/contains 型断言仍会通过，等于没测到 URL 构造。
        when(req.getUriStr()).thenReturn(uri.getPath());
        return req;
    }

    @Test
    void remote_returnsHostAndPort() {
        WebServerHttpRequest req = mockRequest(URI.create("http://localhost:8080/test"),
                new InetSocketAddress("192.168.1.10", 5000), new InetSocketAddress("127.0.0.1", 8080));
        NettyHttpServletRequest servletReq = new NettyHttpServletRequest(req);

        assertEquals("192.168.1.10", servletReq.getRemoteAddr());
        assertEquals("192.168.1.10", servletReq.getRemoteHost());
        assertEquals(5000, servletReq.getRemotePort());
    }

    @Test
    void local_returnsHostAndPort() {
        WebServerHttpRequest req = mockRequest(URI.create("http://localhost:8080/test"),
                new InetSocketAddress("192.168.1.10", 5000), new InetSocketAddress("127.0.0.1", 9090));
        NettyHttpServletRequest servletReq = new NettyHttpServletRequest(req);

        assertEquals("127.0.0.1", servletReq.getLocalAddr());
        assertEquals("127.0.0.1", servletReq.getLocalName());
        assertEquals(9090, servletReq.getLocalPort());
        assertEquals(9090, servletReq.getServerPort(), "server port 应取实际绑定端口而非配置值");
    }

    @Test
    void remoteAddressNull_fallsBackToSuper() {
        WebServerHttpRequest req = mockRequest(URI.create("http://localhost:8080/test"), null, null);
        NettyHttpServletRequest servletReq = new NettyHttpServletRequest(req);
        // 无 remoteAddress，父类回退到固定的 127.0.0.1
        assertEquals("127.0.0.1", servletReq.getRemoteAddr());
        assertEquals("127.0.0.1", servletReq.getLocalAddr());
    }

    @Test
    void scheme_and_isSecure() {
        WebServerHttpRequest httpReq = mockRequest(URI.create("http://localhost:8080/test"), null, null);
        assertFalse(new NettyHttpServletRequest(httpReq).isSecure());
        assertEquals("http", new NettyHttpServletRequest(httpReq).getScheme());

        WebServerHttpRequest httpsReq = mockRequest(URI.create("https://localhost:8443/test"), null, null);
        assertTrue(new NettyHttpServletRequest(httpsReq).isSecure());
        assertEquals("https", new NettyHttpServletRequest(httpsReq).getScheme());
    }

    @Test
    void getRequestURL_nonDefaultPort_includesPort() {
        WebServerHttpRequest req = mockRequest(URI.create("http://localhost:8080/test"),
                new InetSocketAddress("192.168.1.10", 5000), new InetSocketAddress("127.0.0.1", 8080));
        NettyHttpServletRequest servletReq = new NettyHttpServletRequest(req);

        assertEquals("http://localhost:8080/test", servletReq.getRequestURL().toString(),
                "非默认端口必须保留，scheme/host/path/port 均需精确");
    }

    @Test
    void getRequestURL_httpPort80_omitsPort() {
        WebServerHttpRequest req = mockRequest(URI.create("http://localhost:80/test"), null,
                new InetSocketAddress("127.0.0.1", 80));
        NettyHttpServletRequest servletReq = new NettyHttpServletRequest(req);

        assertEquals("http://localhost/test", servletReq.getRequestURL().toString(),
                "HTTP 默认端口 80 应省略，且 path 必须完整拼入 URL");
    }

    @Test
    void getRequestURL_httpsPort443_omitsPort() {
        WebServerHttpRequest req = mockRequest(URI.create("https://public.example.com:443/test"), null,
                new InetSocketAddress("127.0.0.1", 443));
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("Host", "public.example.com");
        when(req.getHeaders()).thenReturn(headers);
        NettyHttpServletRequest servletReq = new NettyHttpServletRequest(req);

        assertEquals("https://public.example.com/test", servletReq.getRequestURL().toString(),
                "HTTPS 默认端口 443 应省略，且 scheme/host 应来自实际请求");
    }
}

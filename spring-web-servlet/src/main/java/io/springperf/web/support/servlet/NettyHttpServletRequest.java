package io.springperf.web.support.servlet;

import io.springperf.web.http.WebServerHttpRequest;

import java.net.InetSocketAddress;

public class NettyHttpServletRequest extends PerfHttpServletRequest {

    public NettyHttpServletRequest(WebServerHttpRequest request) {
        super(request);
    }

    // ===================== Network =====================

    @Override
    public String getRemoteAddr() {
        InetSocketAddress addr = (InetSocketAddress) getDelegateRequest().getRemoteAddress();
        return addr != null ? addr.getHostString() : super.getRemoteAddr();
    }

    @Override
    public String getRemoteHost() {
        InetSocketAddress addr = (InetSocketAddress) getDelegateRequest().getRemoteAddress();
        return addr != null ? addr.getHostString() : super.getRemoteHost();
    }

    @Override
    public int getRemotePort() {
        InetSocketAddress addr = (InetSocketAddress) getDelegateRequest().getRemoteAddress();
        return addr != null ? addr.getPort() : super.getRemotePort();
    }

    @Override
    public String getLocalAddr() {
        InetSocketAddress addr = (InetSocketAddress) getDelegateRequest().getLocalAddress();
        return addr != null ? addr.getHostString() : super.getLocalAddr();
    }

    @Override
    public String getLocalName() {
        InetSocketAddress addr = (InetSocketAddress) getDelegateRequest().getLocalAddress();
        return addr != null ? addr.getHostString() : super.getLocalName();
    }

    @Override
    public int getLocalPort() {
        InetSocketAddress addr = (InetSocketAddress) getDelegateRequest().getLocalAddress();
        return addr != null ? addr.getPort() : super.getLocalPort();
    }

    @Override
    public int getServerPort() {
        // 返回实际绑定端口而非配置值（server.port）：
        // RANDOM_PORT/management 隔离等场景下配置端口为 0 或不同于实际绑定端口，
        // getRequestURL() 依赖此端口拼接出正确的请求 URL
        InetSocketAddress addr = (InetSocketAddress) getDelegateRequest().getLocalAddress();
        return addr != null ? addr.getPort() : super.getServerPort();
    }

    @Override
    public String getScheme() {
        return getDelegateRequest().getURI().getScheme();
    }

    @Override
    public boolean isSecure() {
        return "https".equals(getScheme());
    }

    // ===================== URL =====================

    @Override
    public StringBuffer getRequestURL() {
        StringBuffer sb = new StringBuffer();
        sb.append(getScheme()).append("://");
        sb.append(getServerName());
        int port = getServerPort();
        String scheme = getScheme();
        if ((!"http".equals(scheme) || port != 80) && (!"https".equals(scheme) || port != 443)) {
            sb.append(':').append(port);
        }
        sb.append(getRequestURI());
        return sb;
    }
}
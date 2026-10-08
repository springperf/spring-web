package io.springperf.web.support.servlet;

import io.springperf.web.http.WebServerHttpRequest;

import java.net.InetSocketAddress;
import java.net.URI;

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
        // 信任转发头时（server.forward-headers-strategy 非 NONE），端口取外部权威中的值，
        // 而非本地监听端口——否则 Location/绝对 URL 会带上内网端口。
        if (isForwardedHeadersTrusted()) {
            URI uri = getDelegateRequest().getURI();
            int port = uri.getPort();
            if (port > 0) {
                return port;
            }
            // URI 未显式带端口：按 scheme 的默认端口（转发头省略默认端口是合法写法）
            String scheme = uri.getScheme();
            if ("https".equalsIgnoreCase(scheme)) {
                return 443;
            }
            if ("http".equalsIgnoreCase(scheme)) {
                return 80;
            }
        }
        // 默认（不信任转发头）：返回实际绑定端口而非配置值（server.port）：
        // RANDOM_PORT/management 隔离等场景下配置端口为 0 或不同于实际绑定端口，
        // getRequestURL() 依赖此端口拼接出正确的请求 URL
        InetSocketAddress addr = (InetSocketAddress) getDelegateRequest().getLocalAddress();
        return addr != null ? addr.getPort() : super.getServerPort();
    }

    @Override
    public String getServerName() {
        // 同上：仅信任转发头时改从外部权威（X-Forwarded-Host / Forwarded: host=）取主机名，
        // 否则保持「Host 头」的既有语义。
        if (isForwardedHeadersTrusted()) {
            String host = getDelegateRequest().getURI().getHost();
            if (host != null) {
                return host;
            }
        }
        return super.getServerName();
    }

    @Override
    public String getContextPath() {
        // 网关剥离前缀转发时（X-Forwarded-Prefix），应用侧看到的 contextPath 应是外部前缀，
        // 否则应用内部生成的相对链接会丢掉网关前缀。仅在信任转发头时生效。
        String prefix = forwardedPrefix();
        return prefix != null ? prefix : super.getContextPath();
    }

    /**
     * 取 {@code X-Forwarded-Prefix}（信任转发头时）。返回规范化后的前缀：去掉尾部 {@code /}，保证以 {@code /} 开头； 空值或仅 {@code /} 视为无前缀（返回 null）。
     */
    private String forwardedPrefix() {
        if (!isForwardedHeadersTrusted()) {
            return null;
        }
        String prefix = getDelegateRequest().getHeaders().getFirst("X-Forwarded-Prefix");
        if (prefix == null) {
            return null;
        }
        prefix = prefix.trim();
        if (prefix.isEmpty() || "/".equals(prefix)) {
            return null;
        }
        if (!prefix.startsWith("/")) {
            prefix = "/" + prefix;
        }
        while (prefix.length() > 1 && prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    /**
     * 是否信任转发头：与 {@link PerfHttpServletRequest#isForwardedHeadersTrusted} 同一判定（{@code
     * server.forward-headers-strategy} 非 NONE/FALSE 且非空）。
     */
    private boolean isForwardedHeadersTrusted() {
        return PerfHttpServletRequest.isForwardedHeadersTrusted(getDelegateRequest().getWebContext());
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

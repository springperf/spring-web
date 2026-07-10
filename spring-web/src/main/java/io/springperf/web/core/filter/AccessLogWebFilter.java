package io.springperf.web.core.filter;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;

/**
 * 访问日志 WebFilter，记录每个请求的方法、URI、状态码、处理耗时和客户端地址。
 * <p>通过 {@code server.accesslog.enabled=false} 关闭。</p>
 * <p>Order 设为 {@code Integer.MIN_VALUE} 使其在 Filter 链中最早执行（最外层包裹），
 * 从而能统计包括其他 Filter 在内的完整处理耗时。</p>
 */
@Slf4j
public class AccessLogWebFilter implements WebFilter {

    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response, FilterChain chain) throws Exception {
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
            int status = response.getStatus() != null ? response.getStatus().value() : 0;
            String remoteAddr = getRemoteAddr(request);
            String ua = request.getHeaders().getFirst("User-Agent");
            log.info("{} {} {} {}ms {} \"{}\"",
                    remoteAddr,
                    request.getMethodValue(),
                    request.getUriStrWithQuery(),
                    elapsedMs,
                    status,
                    ua != null ? ua : "-");
        }
    }

    private static String getRemoteAddr(WebServerHttpRequest request) {
        InetSocketAddress addr = request.getRemoteAddress();
        return addr != null ? addr.getHostString() : "-";
    }

    @Override
    public int getOrder() {
        return Integer.MIN_VALUE;
    }
}
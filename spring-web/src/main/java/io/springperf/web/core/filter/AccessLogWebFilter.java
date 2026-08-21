package io.springperf.web.core.filter;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 访问日志 WebFilter，记录每个请求的方法、URI、状态码、处理耗时和客户端地址。
 * <p>通过 {@code server.accesslog.enabled=false} 关闭。</p>
 * <p>通过 {@code server.accesslog.format} 配置格式，支持以下占位符：</p>
 * <ul>
 *   <li>{@code %h} — 远程地址</li>
 *   <li>{@code %m} — HTTP 方法</li>
 *   <li>{@code %U} — URI（含查询参数）</li>
 *   <li>{@code %T} — 处理耗时（毫秒）</li>
 *   <li>{@code %s} — HTTP 状态码</li>
 *   <li>{@code %u} — User-Agent 请求头</li>
 * </ul>
 * <p>Order 设为 {@code Integer.MIN_VALUE} 使其在 Filter 链中最早执行（最外层包裹），
 * 从而能统计包括其他 Filter 在内的完整处理耗时。</p>
 */
@Slf4j
public class AccessLogWebFilter implements WebFilter {

    private static final String DEFAULT_FORMAT = "%h %m %U %Tms %s \"%u\"";

    private final List<Segment> segments;

    /**
     * 使用默认格式创建访问日志过滤器。
     */
    public AccessLogWebFilter() {
        this(DEFAULT_FORMAT);
    }

    /**
     * 使用指定格式创建访问日志过滤器。
     *
     * @param format 格式字符串，支持 %h、%m、%U、%T、%s、%u 占位符
     */
    public AccessLogWebFilter(String format) {
        this.segments = parseFormat(format != null ? format : DEFAULT_FORMAT);
    }

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

            // Build log message from segments
            StringBuilder sb = new StringBuilder(64);
            for (Segment seg : segments) {
                seg.append(sb, request, elapsedMs, status, remoteAddr, ua);
            }
            log.info(sb.toString());
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

    // ====== Format parsing ======

    private static List<Segment> parseFormat(String format) {
        List<Segment> result = new ArrayList<>();
        int len = format.length();
        int i = 0;
        StringBuilder literal = new StringBuilder();

        while (i < len) {
            char c = format.charAt(i);
            if (c == '%' && i + 1 < len) {
                char next = format.charAt(i + 1);
                Token token = Token.fromChar(next);
                if (token != null) {
                    // Flush pending literal
                    if (literal.length() > 0) {
                        result.add(new LiteralSegment(literal.toString()));
                        literal.setLength(0);
                    }
                    result.add(new TokenSegment(token));
                    i += 2;
                    continue;
                }
            }
            literal.append(c);
            i++;
        }

        // Flush trailing literal
        if (literal.length() > 0) {
            result.add(new LiteralSegment(literal.toString()));
        }

        return result;
    }

    private enum Token {
        REMOTE_ADDR('h'),
        METHOD('m'),
        URI('U'),
        ELAPSED_MS('T'),
        STATUS('s'),
        USER_AGENT('u');

        final char ch;

        Token(char ch) {
            this.ch = ch;
        }

        static Token fromChar(char c) {
            for (Token t : values()) {
                if (t.ch == c) {
                    return t;
                }
            }
            return null;
        }
    }

    private interface Segment {
        void append(StringBuilder sb, WebServerHttpRequest request, long elapsedMs,
                    int status, String remoteAddr, String userAgent);
    }

    private static class LiteralSegment implements Segment {
        final String text;

        LiteralSegment(String text) {
            this.text = text;
        }

        @Override
        public void append(StringBuilder sb, WebServerHttpRequest request, long elapsedMs,
                           int status, String remoteAddr, String userAgent) {
            sb.append(text);
        }
    }

    private static class TokenSegment implements Segment {
        final Token token;
        final Function<Context, String> resolver;

        TokenSegment(Token token) {
            this.token = token;
            switch (token) {
                case REMOTE_ADDR:
                    this.resolver = ctx -> ctx.remoteAddr;
                    break;
                case METHOD:
                    this.resolver = ctx -> ctx.request.getMethodValue();
                    break;
                case URI:
                    this.resolver = ctx -> ctx.request.getUriStrWithQuery();
                    break;
                case ELAPSED_MS:
                    this.resolver = ctx -> String.valueOf(ctx.elapsedMs);
                    break;
                case STATUS:
                    this.resolver = ctx -> String.valueOf(ctx.status);
                    break;
                case USER_AGENT:
                    this.resolver = ctx -> ctx.userAgent != null ? ctx.userAgent : "-";
                    break;
                default:
                    this.resolver = ctx -> "";
            }
        }

        @Override
        public void append(StringBuilder sb, WebServerHttpRequest request, long elapsedMs,
                           int status, String remoteAddr, String userAgent) {
            sb.append(resolver.apply(new Context(request, elapsedMs, status, remoteAddr, userAgent)));
        }
    }

    private static class Context {
        final WebServerHttpRequest request;
        final long elapsedMs;
        final int status;
        final String remoteAddr;
        final String userAgent;

        Context(WebServerHttpRequest request, long elapsedMs, int status,
                String remoteAddr, String userAgent) {
            this.request = request;
            this.elapsedMs = elapsedMs;
            this.status = status;
            this.remoteAddr = remoteAddr;
            this.userAgent = userAgent;
        }
    }
}
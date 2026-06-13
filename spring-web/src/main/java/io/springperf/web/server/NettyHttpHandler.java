package io.springperf.web.server;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.NettyServerHttpRequest;
import io.springperf.web.http.NettyServerHttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

/**
 * Netty 入站处理器。职责仅限于：
 * <ol>
 *   <li>解析 URI、校验 contextPath</li>
 *   <li>创建 request/response 对象（NettyServerHttpRequest 构造时自动 retain）</li>
 *   <li>委托 {@link HttpHandler#httpHandle} 执行实际处理</li>
 *   <li>异常兜底（ResponseStatusException → 特定状态码，其余 → 500）</li>
 * </ol>
 * <p>contextPath 为空字符串时不执行前缀校验。</p>
 */
@Slf4j
@ChannelHandler.Sharable
public class NettyHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final WebContext webContext;
    private final String contextPath;
    private final HttpHandler handler;
    private volatile boolean shuttingDown;

    public NettyHttpHandler(WebContext webContext, String contextPath, HttpHandler handler) {
        this.webContext = webContext;
        this.contextPath = contextPath;
        this.handler = handler;
    }

    /**
     * 标记服务器进入关闭状态。此后新到达的请求直接返回 503 Service Unavailable。
     */
    public void setShuttingDown() {
        this.shuttingDown = true;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctxNetty, FullHttpRequest msg) {
        // 关闭中：拒绝新请求
        if (shuttingDown) {
            NettyServerHttpResponse resp = new NettyServerHttpResponse(webContext, ctxNetty, false);
            try {
                resp.sendError(HttpStatus.SERVICE_UNAVAILABLE, "Server is shutting down");
            } catch (Exception ignored) {
            }
            return;
        }

        NettyServerHttpResponse resp = new NettyServerHttpResponse(webContext, ctxNetty, HttpUtil.isKeepAlive(msg));
        try {
            // 1. 解析 URI，提取路径（去掉 query string）
            String rawUri = msg.uri();
            String requestPath = rawUri.contains("?") ? rawUri.substring(0, rawUri.indexOf('?')) : rawUri;

            // 2. contextPath 校验：若配置了 contextPath，请求路径必须以 contextPath 开头
            String resolvedPath;
            if (contextPath.isEmpty()) {
                resolvedPath = requestPath;
            } else {
                if (requestPath.equals(contextPath)) {
                    resolvedPath = "/";
                } else if (requestPath.startsWith(contextPath + "/")) {
                    resolvedPath = requestPath.substring(contextPath.length());
                } else {
                    resp.sendError(HttpStatus.NOT_FOUND, "Not Found");
                    return;
                }
            }

            // 3. 用预解析的 path 创建请求，跳过 BaseWebServerHttpRequest 中的 contextPath 校验
            NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctxNetty, msg, resolvedPath);
            resp.setTimeout();
            // 4. 委托给实际处理逻辑
            handler.httpHandle(req, resp);
        } catch (Throwable e) {
            log.error("NH EXCEPTION", e);
            try {
                resp.sendError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
            } catch (Exception ignored) {
                // sendError 失败无需额外处理
            }
        }
    }
}
package io.springperf.web.server;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

/**
 * HTTP 请求处理接口，将"请求处理"抽象为策略。
 * <p>由 {@link NettyHttpHandler} 持有并委托调用，实现类负责实际业务处理：
 * <ul>
 *   <li>{@link io.springperf.web.core.DispatcherHandler} — 主端口请求处理（路由匹配 → Filter 链 → 参数解析 → 控制器调用）</li>
 *   <li>{@code ManagementDispatcherHandler} — 管理端口请求处理（Actuator 端点路由）</li>
 * </ul>
 */
@FunctionalInterface
public interface HttpHandler {

    /**
     * 处理 HTTP 请求。
     * @param request  HTTP 请求
     * @param response HTTP 响应
     */
    void httpHandle(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception;
}
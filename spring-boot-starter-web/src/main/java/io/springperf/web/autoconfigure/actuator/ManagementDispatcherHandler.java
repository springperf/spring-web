package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.cors.CorsUtils;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.server.HttpHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 管理端口的 DispatcherHandler。
 * <p>职责参照 {@link DispatcherHandler}：basePath 校验、路由匹配、CORS 处理、404/405 响应，
 * 匹配成功后委托父类的 {@link #doHandle} 执行调用并刷新响应。
 * 跳过拦截器、参数解析等主端口步骤。</p>
 * <p>路由注册委托给 {@link ManagementMappingRegistry}，由
 * {@link PerfEndpointHandlerMapping#afterPropertiesSet()} 在初始化阶段注入。</p>
 */
@Slf4j
public class ManagementDispatcherHandler extends DispatcherHandler implements HttpHandler {

    private final ManagementMappingRegistry managementMappingRegistry;
    private final String basePath;

    public ManagementDispatcherHandler(WebContext webContext, String basePath,
                                       ManagementMappingRegistry managementMappingRegistry) {
        initWithWebContext(webContext);
        this.basePath = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        this.managementMappingRegistry = managementMappingRegistry;
    }

    /**
     * 注册 Actuator 路由到管理端口路由表。
     */
    public void registerRoute(PathMappingContext ctx) {
        managementMappingRegistry.registerMapping(ctx);
        log.debug("Registered actuator route on management port: {}", ctx.getPathRule());
    }

    /**
     * 所有路由注册完成后，构建优化器管线。
     * <p>由 {@link PerfEndpointHandlerMapping#afterPropertiesSet()} 在所有路由注册完后调用。</p>
     */
    public void buildOptimizerPipeline() {
        managementMappingRegistry.buildOptimizerPipeline();
    }

    /**
     * HttpHandler 接口实现，直接委托给 {@link #handle}。
     */
    @Override
    public void httpHandle(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        handle(request, response);
    }

    /**
     * 处理管理端口的请求入口。职责：
     * <ol>
     *   <li>basePath 校验</li>
     *   <li>路由匹配（复用 ManagementMappingRegistry 优化器管线）</li>
     *   <li>CORS 预检处理</li>
     *   <li>匹配成功 → {@link #doHandle}</li>
     *   <li>404/405 响应</li>
     * </ol>
     */
    public void handle(WebServerHttpRequest req, WebServerHttpResponse resp) {
        try {
            // 通过 MappingRegistry 优化器管线进行路由匹配
            MappingResult result = managementMappingRegistry.mapping(req);

            if (result.isMatched()) {
                doHandle(req, resp, result.getMatchedContext());
                return;
            }

            // CORS 预检请求处理：路由路径匹配但 HTTP 方法不匹配时，仍可处理 OPTIONS 预检
            if (result.isPathMatched() && corsRegistry != null
                    && CorsUtils.isPreFlightRequest(req)) {
                PathMappingContext[] contexts = result.getPathMatchedContexts();
                if (contexts.length > 0) {
                    PathMappingContext.set(req, contexts[0]);
                    if (corsRegistry.corsHandle(req, resp)) {
                        return;
                    }
                }
            }

            // 405 / 404
            if (result.isMethodMismatch()) {
                sendError(resp, HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed");
            } else {
                sendError(resp, HttpStatus.NOT_FOUND, "Not Found");
            }
        } catch (ResponseStatusException e) {
            sendError(resp, e.getStatus(), e.getReason());
        } catch (Throwable e) {
            log.error("Management dispatcher error", e);
            sendError(resp, HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
        } finally {
            req.complete();
        }
    }

    @Override
    protected void doHandle(WebServerHttpRequest req, WebServerHttpResponse resp,
                             PathMappingContext mappingContext) {
        Object result = null;
        Throwable exception = null;
        try {
            if (corsRegistry.corsHandle(req, resp)) {
                return;
            }
            result = doInvokeCore(req, resp, mappingContext, null);
        } catch (Throwable ex) {
            exception = ex;
            handleException(ex, req, resp);
        } finally {
            flushResponse(resp);
        }
    }

    private static void sendError(WebServerHttpResponse resp, HttpStatus status, String reason) {
        try {
            resp.sendError(status, reason);
        } catch (Exception ignored) {
            log.warn("Failed to send error response on management port", ignored);
        }
    }
}
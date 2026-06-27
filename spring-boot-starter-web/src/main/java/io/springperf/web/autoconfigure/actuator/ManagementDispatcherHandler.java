package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.filter.WebFilterRegistry;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

/**
 * 管理端口的 DispatcherHandler。
 * <p>职责参照 {@link DispatcherHandler}：basePath 校验、路由匹配、CORS 处理、404/405 响应，
 * 匹配成功后委托父类的 {@link #doHandle} 执行调用并刷新响应。
 * 跳过拦截器、参数解析等主端口步骤。</p>
 * <p>路由注册委托给 {@link ManagementMappingRegistry}，由
 * {@link ActuatorEndpointHandlerMapping#afterPropertiesSet()} 在初始化阶段注入。</p>
 */
@Slf4j
public class ManagementDispatcherHandler extends DispatcherHandler {

    private final ManagementMappingRegistry managementMappingRegistry;
    private final String basePath;

    public ManagementDispatcherHandler(WebContext webContext, String basePath,
                                       ManagementMappingRegistry managementMappingRegistry) {
        initWithWebContext(webContext);
        this.basePath = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        this.managementMappingRegistry = managementMappingRegistry;
    }

    @Override
    public void initComponentPhase2() throws Exception {
        super.initComponentPhase2();
        mappingRegistry = managementMappingRegistry;
        this.webFilterRegistry = new WebFilterRegistry(this);
    }

    /**
     * 注册 Actuator 路由到管理端口路由表。
     */
    public void registerRoute(PathMappingContext ctx) {
        managementMappingRegistry.registerMapping(ctx);
    }

    /**
     * 所有路由注册完成后，构建优化器管线。
     * <p>由 {@link ActuatorEndpointHandlerMapping#afterPropertiesSet()} 在所有路由注册完后调用。</p>
     */
    public void buildOptimizerPipeline() {
        managementMappingRegistry.buildOptimizerPipeline();
    }

    @Override
    protected void handleWithMappingResult(WebServerHttpRequest req, WebServerHttpResponse resp, MappingResult mappingResult) {
        if (!mappingResult.isMatched()) {
            handleWithNoFullMatch(req, resp, mappingResult);
            return;
        }
        try {
            if (corsRegistry.corsHandle(req, resp)) {
                return;
            }
            PathMappingContext mappingContext = mappingResult.getMatchedContext();
            Object result = mappingContext.invoke(null, req, resp);
            returnValueResolverRegistry.resolveReturnValue(result, mappingContext, req, resp);
        } catch (Throwable ex) {
            handleException(ex, req, resp);
        } finally {
            flushResponse(resp);
        }
    }

    protected void handleOnNoMatchMappingContext(WebServerHttpRequest req, WebServerHttpResponse resp, MappingResult result) {
        // 405 / 404
        if (result.isMethodMismatch()) {
            sendError(resp, HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed");
        } else {
            sendError(resp, HttpStatus.NOT_FOUND, "Not Found");
        }
    }
}
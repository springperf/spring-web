package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 管理端口的 Actuator 映射注册表。
 * <p>继承 {@link MappingRegistry} 复用其路由优化管线（optimizer pipeline），
 * 但覆盖 {@link #initComponentPhase1()} 和 {@link #initComponentPhase2()}，
 * 不从 ApplicationContext 扫描 {@code @Controller}，不参与主 WebContext 生命周期。</p>
 * <p>路由由 {@link ActuatorEndpointHandlerMapping#afterPropertiesSet()} 通过
 * {@link #registerMapping(PathMappingContext)} 注入，注册完成后显式调用
 * {@link #buildOptimizerPipeline()} 触发优化。</p>
 */
@Slf4j
public class ManagementMappingRegistry extends MappingRegistry {

    @Override
    public void initComponentPhase1() {
        // no-op：管理端口不从 ApplicationContext 扫描 @Controller/@RestController
        // 路由由 PerfEndpointHandlerMapping.afterPropertiesSet() 通过 registerMapping 注入
    }

    @Override
    public void initComponentPhase2() {
        // no-op：优化由 buildOptimizerPipeline() 显式触发
    }

    /**
     * 在所有 Actuator 路由注册完成后，构建优化器管线。
     * <p>必须在所有路由注册完成后调用一次。调用后可通过 {@link #mapping(WebServerHttpRequest)}
     * 执行路由匹配。</p>
     */
    public void buildOptimizerPipeline() {
        List<PathMappingContext> contexts = getMappingContextList();
        if (contexts.isEmpty()) {
            log.warn("No actuator routes registered, skipping optimizer pipeline build");
            return;
        }
        log.info("Building management port optimizer pipeline with {} routes", contexts.size());
        optimizeMapping(contexts);
    }
}

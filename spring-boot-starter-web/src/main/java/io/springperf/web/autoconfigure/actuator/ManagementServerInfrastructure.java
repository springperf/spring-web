package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.context.WebContext;

/**
 * 管理端口的服务器基础设施持有者。
 * <p>内部创建 {@link ManagementDispatcherHandler} 和 {@link ManagementMappingRegistry}，
 * 但不继承 {@link io.springperf.web.core.DispatcherHandler}，因此不会被
 * {@code List<DispatcherHandler>} 自动收集，从而切断循环依赖链。</p>
 */
public class ManagementServerInfrastructure {

    private final ManagementDispatcherHandler dispatcherHandler;
    private final ManagementMappingRegistry mappingRegistry;

    public ManagementServerInfrastructure(WebContext webContext, String basePath) {
        this.mappingRegistry = new ManagementMappingRegistry();
        this.dispatcherHandler = new ManagementDispatcherHandler(webContext, basePath, mappingRegistry);
        webContext.registerWebComponent(dispatcherHandler);
    }

    public ManagementDispatcherHandler getDispatcherHandler() {
        return dispatcherHandler;
    }

    public ManagementMappingRegistry getMappingRegistry() {
        return mappingRegistry;
    }
}
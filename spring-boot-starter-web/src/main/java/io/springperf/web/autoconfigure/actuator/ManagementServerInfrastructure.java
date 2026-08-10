package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.context.LifecycleWebComponent;
import io.springperf.web.context.WebContext;

/**
 * 管理端口的服务器基础设施持有者。
 * <p>内部创建 {@link ManagementDispatcherHandler} 和 {@link ManagementMappingRegistry}。
 * 仅将自身（而非 ManagementDispatcherHandler）注册为 WebComponent：ManagementDispatcherHandler
 * 是 {@link io.springperf.web.core.DispatcherHandler} 子类，若注册进共享 WebContext，会被主端口
 * {@code webContext.getWebComponent(DispatcherHandler.class)} 按 assignable 收集，与主 dispatcher
 * 共享查找空间 → order 相同、取值取决于 map 迭代顺序 → 主端口可能拿到管理 dispatcher，
 * 业务路由全部 404/405 静默退化。</p>
 * <p>自身不继承 DispatcherHandler，也不会被 {@code List<DispatcherHandler>} 自动收集，
 * 从而切断循环依赖链，同时经 {@link #initComponentPhase2()} 转发驱动内部 dispatcher 的装配。</p>
 */
public class ManagementServerInfrastructure implements LifecycleWebComponent {

    private final ManagementDispatcherHandler dispatcherHandler;
    private final ManagementMappingRegistry mappingRegistry;

    public ManagementServerInfrastructure(WebContext webContext, String basePath) {
        this.mappingRegistry = new ManagementMappingRegistry();
        this.dispatcherHandler = new ManagementDispatcherHandler(webContext, basePath, mappingRegistry);
        // 只注册自身，不让 ManagementDispatcherHandler 进入共享 DispatcherHandler 查找空间
        webContext.registerWebComponent(this);
    }

    /**
     * 生命周期 Phase 2：转发到内部 dispatcher，完成跨组件装配（mappingRegistry 切换）。
     */
    @Override
    public void initComponentPhase2() throws Exception {
        dispatcherHandler.initComponentPhase2();
    }

    public ManagementDispatcherHandler getDispatcherHandler() {
        return dispatcherHandler;
    }

    public ManagementMappingRegistry getMappingRegistry() {
        return mappingRegistry;
    }
}
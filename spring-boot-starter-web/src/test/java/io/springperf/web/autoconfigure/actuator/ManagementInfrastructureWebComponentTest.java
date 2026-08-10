package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.mapping.MappingRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 正向验证 R2-5（P1-18）修复行为：{@link ManagementServerInfrastructure} 自身作为
 * WebComponent 注册（生命周期可驱动），并转发 Phase 2 到内部 dispatcher。
 */
class ManagementInfrastructureWebComponentTest {

    private WebContext newWebContext() {
        ApplicationProperties props = new ApplicationProperties();
        props.setEnvironment(new MockEnvironment());
        WebContext webContext = new WebContext(new DispatcherHandler(), props);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(any(Class.class))).thenReturn(Collections.emptyMap());
        webContext.setApplicationContext(ctx);
        return webContext;
    }

    @Test
    void infrastructure_registersItselfAsWebComponent() {
        WebContext webContext = newWebContext();

        ManagementServerInfrastructure infra = new ManagementServerInfrastructure(webContext, "/actuator");

        // 注册自身（非 DispatcherHandler 子类），生命周期照常驱动 phase2 转发
        assertSame(infra, webContext.getWebComponent(ManagementServerInfrastructure.class));
    }

    @Test
    void infrastructure_phase2_forwardsToDispatcher() throws Exception {
        WebContext webContext = newWebContext();
        ManagementServerInfrastructure infra = new ManagementServerInfrastructure(webContext, "/actuator");
        ManagementDispatcherHandler dispatcher = infra.getDispatcherHandler();

        // 构造后 mappingRegistry 尚未切换（phase2 由 startLifecycle 驱动）
        assertNotSame(infra.getMappingRegistry(), mappingRegistryOf(dispatcher));

        infra.initComponentPhase2();

        // phase2 转发后，管理 dispatcher 指向管理专用 MappingRegistry
        assertSame(infra.getMappingRegistry(), mappingRegistryOf(dispatcher));
    }

    private static MappingRegistry mappingRegistryOf(DispatcherHandler dispatcher) throws Exception {
        Field field = DispatcherHandler.class.getDeclaredField("mappingRegistry");
        field.setAccessible(true);
        return (MappingRegistry) field.get(dispatcher);
    }
}

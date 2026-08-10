package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回归 R2-5（P1-18）：Management dispatcher 不得污染共享 WebContext 的 DispatcherHandler 查找空间。
 * <p>修复前 {@link ManagementServerInfrastructure} 构造函数把 {@link ManagementDispatcherHandler}
 * 注册进共享 WebContext，而主端口 {@code NettyHttpServer.start()} 用
 * {@code webContext.getWebComponent(DispatcherHandler.class)}（assignable 收集）取主 dispatcher，
 * 两者 order 相同 → 返回结果取决于 map 迭代顺序 → 主端口可能拿到管理 dispatcher，
 * 业务路由全部 404/405 静默退化。</p>
 */
class ManagementDispatcherIsolationTest {

    /**
     * 构造真实 WebContext：主 dispatcher 已注册；ManagementDispatcherHandler 构造时
     * 通过 getWebComponentWithDefault 拉取默认组件，需要 ctx.getBeansOfType 返回空（走 default 注册路径）。
     */
    private WebContext newWebContext() {
        ApplicationProperties props = new ApplicationProperties();
        props.setEnvironment(new MockEnvironment());
        WebContext webContext = new WebContext(new DispatcherHandler(), props);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(any(Class.class))).thenReturn(Collections.emptyMap());
        webContext.setApplicationContext(ctx);
        return webContext;
    }

    /**
     * 反向验证核心断言：构造 ManagementServerInfrastructure 后，共享 WebContext 中
     * DispatcherHandler 可查找实例必须恰好 1 个（主 dispatcher）。修复前 size==2，
     * 主端口 getWebComponent(DispatcherHandler.class) 返回值不确定。
     */
    @Test
    void managementDispatcher_doesNotPolluteMainDispatcherLookup() {
        WebContext webContext = newWebContext();
        DispatcherHandler main = webContext.getDispatcherHandler();

        new ManagementServerInfrastructure(webContext, "/actuator");

        assertEquals(1, webContext.getWebComponents(DispatcherHandler.class).size(),
                "Management dispatcher must not be visible to main-port DispatcherHandler lookup");
        assertSame(main, webContext.getWebComponent(DispatcherHandler.class),
                "Main port must resolve the main dispatcher, not the management dispatcher");
    }
}

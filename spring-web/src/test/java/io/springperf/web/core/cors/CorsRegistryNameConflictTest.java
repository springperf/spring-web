package io.springperf.web.core.cors;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回归：多个 {@link CorsRegistration} 必须全部存活于组件容器。
 * <p>修复前 {@link CorsRegistration} 未覆盖 {@code getComponentName()}，多个注册共享默认
 * 简单类名，在 {@link WebComponentContainer#registerWebComponent} 中按名互斥、相互覆盖，
 * 仅最后一个保留——多路径 CORS 配置会静默丢失。此测试锁定唯一组件名的契约。</p>
 */
class CorsRegistryNameConflictTest {

    private WebContext createWebContext() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.CONTEXT_PATH, "/")).thenReturn("/");
        WebContext wc = new WebContext(mock(DispatcherHandler.class), props);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(any())).thenReturn(Collections.emptyMap());
        wc.setCtx(ctx);
        return wc;
    }

    @Test
    void multipleRegistrations_allSurviveContainer() throws Exception {
        WebContext wc = createWebContext();
        CorsRegistry registry = new CorsRegistry();
        registry.addMapping("/api/**").allowedOrigins("http://a.com");
        registry.addMapping("/admin/**").allowedOrigins("http://b.com");
        registry.addMapping("/public/**").allowedOrigins("http://c.com");
        registry.initWithWebContext(wc);
        registry.initComponentPhase1();
        registry.initComponentPhase2();

        List<CorsRegistration> registrations = registry.getWebComponents(CorsRegistration.class);
        assertEquals(3, registrations.size(), "三个 CORS 注册应全部存活");
    }

    @Test
    void samePathPattern_secondRegistrationWins_dedupByComponentName() throws Exception {
        WebContext wc = createWebContext();
        CorsRegistry registry = new CorsRegistry();
        registry.addMapping("/api/**").allowedOrigins("http://a.com").maxAge(100);
        registry.addMapping("/api/**").allowedOrigins("http://b.com").maxAge(200);
        registry.initWithWebContext(wc);
        registry.initComponentPhase1();
        registry.initComponentPhase2();

        // 同一路径模式共享组件名（按 pathPattern 唯一化），后注册者覆盖前注册者
        List<CorsRegistration> registrations = registry.getWebComponents(CorsRegistration.class);
        assertEquals(1, registrations.size());
        assertTrue(registrations.get(0).getCorsConfiguration().getAllowedOrigins().contains("http://b.com"),
                "同路径后注册配置应生效");
    }

    @Test
    void actuatorCorsConfigurations_allSurvive() throws Exception {
        WebContext wc = createWebContext();
        CorsRegistry registry = new CorsRegistry();
        registry.addActuatorCorsConfiguration("/actuator/health", new org.springframework.web.cors.CorsConfiguration());
        registry.addActuatorCorsConfiguration("/actuator/info", new org.springframework.web.cors.CorsConfiguration());
        registry.initWithWebContext(wc);
        registry.initComponentPhase1();
        registry.initComponentPhase2();

        assertEquals(2, registry.getWebComponents(CorsRegistration.class).size());
    }
}

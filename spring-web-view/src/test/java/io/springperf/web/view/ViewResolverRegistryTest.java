package io.springperf.web.view;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.HandlerMethod;

import java.util.Collections;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ViewResolverRegistryTest {

    private WebContext webContext;
    private ViewResolverRegistry registry;
    private ApplicationProperties props;

    @BeforeEach
    void setUp() {
        props = mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.CONTEXT_PATH, "/")).thenReturn("/");
        when(props.getBoolean(PropertiesConstant.CHECK_ON_STARTUP, true)).thenReturn(true);
        webContext = new WebContext(mock(DispatcherHandler.class), props);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(any())).thenReturn(Collections.emptyMap());
        webContext.setCtx(ctx);
        registry = new ViewResolverRegistry();
    }

    @Test
    void hasViewResolvers_false_byDefault() {
        assertFalse(registry.hasViewResolvers());
    }

    @Test
    void hasViewResolvers_true_afterRegisteringResolver() {
        ViewResolver resolver = mock(ViewResolver.class);
        when(resolver.getComponentName()).thenReturn("testResolver");
        when(resolver.isEnabled()).thenReturn(true);
        registry.registerWebComponent(resolver);
        assertTrue(registry.hasViewResolvers());
    }

    @Test
    void hasViewResolvers_false_whenAllResolversDisabled() {
        ViewResolver disabled = mock(ViewResolver.class);
        when(disabled.getComponentName()).thenReturn("disabledResolver");
        when(disabled.isEnabled()).thenReturn(false);
        registry.registerWebComponent(disabled);
        assertFalse(registry.hasViewResolvers(), "禁用引擎不应让启动期校验误判为「有可用引擎」");
    }

    @Test
    void resolve_nullViewName_returnsNull() {
        assertNull(registry.resolve(null, mock(WebServerHttpRequest.class)));
    }

    @Test
    void resolve_noResolvers_returnsNull() {
        assertNull(registry.resolve("home", mock(WebServerHttpRequest.class)));
    }

    private WebServerHttpRequest mockReq() {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getLocale()).thenReturn(Locale.US);
        return req;
    }

    /** mock 的布尔默认值是 false，启动期相关用例必须显式桩 isEnabled，否则整段检测逻辑不会被执行。 */
    private static ViewResolver enabledResolver(String name) {
        ViewResolver resolver = mock(ViewResolver.class);
        when(resolver.getComponentName()).thenReturn(name);
        when(resolver.isEnabled()).thenReturn(true);
        return resolver;
    }

    @Test
    void resolve_firstMatchingResolverWins() throws Exception {
        ViewResolver r1 = mock(ViewResolver.class);
        when(r1.getComponentName()).thenReturn("r1");
        when(r1.getOrder()).thenReturn(10);
        ViewResolver r2 = mock(ViewResolver.class);
        when(r2.getComponentName()).thenReturn("r2");
        when(r2.getOrder()).thenReturn(20);
        registry.registerWebComponent(r1);
        registry.registerWebComponent(r2);
        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();

        WebServerHttpRequest req = mockReq();
        View view = mock(View.class);
        when(r1.resolveViewName(eq("home"), any(Locale.class), eq(req))).thenReturn(view);

        assertSame(view, registry.resolve("home", req));
        verify(r2, never()).resolveViewName(any(), any(), any());
    }

    @Test
    void resolve_skipsToNextResolver_whenFirstReturnsNull() throws Exception {
        ViewResolver r1 = mock(ViewResolver.class);
        when(r1.getComponentName()).thenReturn("r1");
        when(r1.getOrder()).thenReturn(10);
        ViewResolver r2 = mock(ViewResolver.class);
        when(r2.getComponentName()).thenReturn("r2");
        when(r2.getOrder()).thenReturn(20);
        registry.registerWebComponent(r1);
        registry.registerWebComponent(r2);
        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();

        WebServerHttpRequest req = mockReq();
        View view = mock(View.class);
        when(r1.resolveViewName(any(), any(), any())).thenReturn(null);
        when(r2.resolveViewName(eq("home"), any(), eq(req))).thenReturn(view);

        assertSame(view, registry.resolve("home", req));
        verify(r1).resolveViewName(eq("home"), eq(Locale.US), eq(req));
    }

    @Test
    void resolve_resolverThrows_propagatesWithCause() throws Exception {
        ViewResolver r1 = mock(ViewResolver.class);
        when(r1.getComponentName()).thenReturn("r1");
        when(r1.getOrder()).thenReturn(10);
        ViewResolver r2 = mock(ViewResolver.class);
        when(r2.getComponentName()).thenReturn("r2");
        when(r2.getOrder()).thenReturn(20);
        registry.registerWebComponent(r1);
        registry.registerWebComponent(r2);
        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();

        WebServerHttpRequest req = mockReq();
        RuntimeException cause = new RuntimeException("template syntax error");
        when(r1.resolveViewName(any(), any(), any())).thenThrow(cause);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> registry.resolve("home", req));
        assertSame(cause, ex.getCause(), "模板语法错误等根因必须保留");
        assertTrue(ex.getMessage().contains("home"));
        verify(r2, never()).resolveViewName(any(), any(), any());
    }

    @Test
    void resolve_nullRequest_usesDefaultLocale() throws Exception {
        ViewResolver r1 = mock(ViewResolver.class);
        when(r1.getComponentName()).thenReturn("r1");
        registry.registerWebComponent(r1);
        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();

        when(r1.resolveViewName(eq("home"), eq(Locale.getDefault()), isNull())).thenReturn(mock(View.class));
        assertNotNull(registry.resolve("home", null));
    }

    @Test
    void hasResponseBody_methodAnnotation_detects() throws Exception {
        class Controller {
            @ResponseBody
            public String json() {
                return "x";
            }
        }
        assertTrue(ViewResolverRegistry.hasResponseBody(Controller.class.getMethod("json"), Controller.class));
    }

    @Test
    void hasResponseBody_classAnnotation_detects() throws Exception {
        @ResponseBody
        class Controller {
            public String json() {
                return "x";
            }
        }
        assertTrue(ViewResolverRegistry.hasResponseBody(Controller.class.getMethod("json"), Controller.class));
    }

    @Test
    void hasResponseBody_noAnnotation_returnsFalse() throws Exception {
        class Controller {
            public String view() {
                return "x";
            }
        }
        assertFalse(ViewResolverRegistry.hasResponseBody(Controller.class.getMethod("view"), Controller.class));
    }

    @Test
    void initComponentPhase3_withoutResolvers_returnsEarly() throws Exception {
        MappingRegistry mappings = spy(new MappingRegistry());
        webContext.registerWebComponent(mappings);
        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        assertDoesNotThrow(registry::initComponentPhase3);
        // 容器里即便有 MappingRegistry，没有启用引擎时也不得进入遍历
        verify(mappings, never()).getMappingContextList();
    }

    @Test
    void initComponentPhase3_noMappingRegistry_returnsEarly() throws Exception {
        registry.registerWebComponent(enabledResolver("r1"));
        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        assertDoesNotThrow(registry::initComponentPhase3);
        // 必须真的越过 hasViewResolvers 守卫，否则「无 MappingRegistry」这条分支从未被执行
        verify(props, atLeastOnce()).getBoolean(PropertiesConstant.CHECK_ON_STARTUP, true);
    }

    @Test
    void initComponentPhase3_withMappingRegistry_iteratesViewMethods() throws Exception {
        registry.registerWebComponent(enabledResolver("r1"));

        MappingRegistry mappingRegistry = spy(new MappingRegistry());
        webContext.registerWebComponent(mappingRegistry);

        class Controller {
            public String home() {
                return "home";
            }

            @ResponseBody
            public String api() {
                return "api";
            }
        }
        HandlerMethod hm = new HandlerMethod(new Controller(), Controller.class.getMethod("home"));
        PathMappingContext viewMapping = new PathMappingContext(hm, Collections.emptyList(), "/home");
        mappingRegistry.registerMapping(viewMapping);
        HandlerMethod hm2 = new HandlerMethod(new Controller(), Controller.class.getMethod("api"));
        PathMappingContext jsonMapping = new PathMappingContext(hm2, Collections.emptyList(), "/api");
        mappingRegistry.registerMapping(jsonMapping);

        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        assertDoesNotThrow(registry::initComponentPhase3);
        // 检测逻辑唯一的可观测产物是 INFO 日志（本模块 SLF4J 为 NOP），故这里锁定「确实进入遍历」；
        // @ResponseBody 筛选正确性由 hasResponseBody_* 与 ViewReturnValueResolverTest 的 VIEW_NAME_KEY 用例守住。
        verify(mappingRegistry, times(1)).getMappingContextList();
    }

    @Test
    void initComponentPhase3_noViewMethods_shortCircuitsDetection() throws Exception {
        registry.registerWebComponent(enabledResolver("r1"));
        MappingRegistry mappingRegistry = spy(new MappingRegistry());
        webContext.registerWebComponent(mappingRegistry);

        class Controller {
            @ResponseBody
            public String api() {
                return "api";
            }

            public int count() {
                return 1;
            }
        }
        PathMappingContext json = mock(PathMappingContext.class);
        when(json.getMethod()).thenReturn(Controller.class.getMethod("api"));
        // getUserClass() 返回 Class<?>，thenReturn 无法过泛型捕获，改用 doReturn
        doReturn(Controller.class).when(json).getUserClass();
        PathMappingContext nonString = mock(PathMappingContext.class);
        when(nonString.getMethod()).thenReturn(Controller.class.getMethod("count"));
        mappingRegistry.registerMapping(json);
        mappingRegistry.registerMapping(nonString);

        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        // 注册/组件初始化期间也可能读过这两个 mapping，只统计检测阶段
        clearInvocations(json, nonString);
        assertDoesNotThrow(registry::initComponentPhase3);

        verify(mappingRegistry, times(1)).getMappingContextList();
        // 非 String 返回值应在第一个条件短路，无需判定注解（覆盖筛选条件的 false 分支）
        verify(nonString).getMethod();
        verify(nonString, never()).getUserClass();
        // @ResponseBody 方法在第二条件被排除；若误入 viewMethods.add 会再取一次 userClass
        verify(json, times(2)).getMethod();
        verify(json, times(1)).getUserClass();
    }

    @Test
    void initComponentPhase3_checkOnStartupDisabled_returnsEarly() throws Exception {
        ApplicationProperties disabledProps = mock(ApplicationProperties.class);
        when(disabledProps.get(PropertiesConstant.CONTEXT_PATH, "/")).thenReturn("/");
        when(disabledProps.getBoolean(PropertiesConstant.CHECK_ON_STARTUP, true)).thenReturn(false);
        WebContext context = new WebContext(mock(DispatcherHandler.class), disabledProps);
        ApplicationContext appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansOfType(any())).thenReturn(Collections.emptyMap());
        context.setCtx(appCtx);

        MappingRegistry mappings = spy(new MappingRegistry());
        context.registerWebComponent(mappings);
        ViewResolverRegistry reg = new ViewResolverRegistry();
        reg.registerWebComponent(enabledResolver("enabledResolver"));
        reg.initWithWebContext(context);
        reg.initComponentPhase1();
        assertDoesNotThrow(reg::initComponentPhase3);
        verify(disabledProps, atLeastOnce()).getBoolean(PropertiesConstant.CHECK_ON_STARTUP, true);
        verify(mappings, never()).getMappingContextList();
    }

    @Test
    void invalidateViewCaches_invokesAllRegisteredResolvers() {
        ViewResolver r1 = mock(ViewResolver.class);
        when(r1.getComponentName()).thenReturn("r1");
        ViewResolver r2 = mock(ViewResolver.class);
        when(r2.getComponentName()).thenReturn("r2");
        registry.registerWebComponent(r1);
        registry.registerWebComponent(r2);
        registry.initWithWebContext(webContext);

        registry.invalidateViewCaches();

        verify(r1).invalidateCache();
        verify(r2).invalidateCache();
    }
}

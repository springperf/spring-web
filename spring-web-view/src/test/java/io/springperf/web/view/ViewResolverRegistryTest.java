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

    @BeforeEach
    void setUp() {
        ApplicationProperties props = mock(ApplicationProperties.class);
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
        registry.registerWebComponent(resolver);
        assertTrue(registry.hasViewResolvers());
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
    }

    @Test
    void resolve_resolverThrows_skipsAndReturnsNull() throws Exception {
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
        when(r1.resolveViewName(any(), any(), any())).thenThrow(new RuntimeException("boom"));
        when(r2.resolveViewName(any(), any(), any())).thenReturn(null);

        assertNull(registry.resolve("home", req));
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
            public String json() { return "x"; }
        }
        assertTrue(ViewResolverRegistry.hasResponseBody(
                Controller.class.getMethod("json"), Controller.class));
    }

    @Test
    void hasResponseBody_classAnnotation_detects() throws Exception {
        @ResponseBody
        class Controller {
            public String json() { return "x"; }
        }
        assertTrue(ViewResolverRegistry.hasResponseBody(
                Controller.class.getMethod("json"), Controller.class));
    }

    @Test
    void hasResponseBody_noAnnotation_returnsFalse() throws Exception {
        class Controller {
            public String view() { return "x"; }
        }
        assertFalse(ViewResolverRegistry.hasResponseBody(
                Controller.class.getMethod("view"), Controller.class));
    }

    @Test
    void initComponentPhase3_withoutResolvers_returnsEarly() throws Exception {
        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        assertDoesNotThrow(registry::initComponentPhase3);
    }

    @Test
    void initComponentPhase3_noMappingRegistry_returnsEarly() throws Exception {
        ViewResolver r1 = mock(ViewResolver.class);
        when(r1.getComponentName()).thenReturn("r1");
        registry.registerWebComponent(r1);
        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        assertDoesNotThrow(registry::initComponentPhase3);
    }

    @Test
    void initComponentPhase3_withMappingRegistry_detectsViewMethods() throws Exception {
        ViewResolver r1 = mock(ViewResolver.class);
        when(r1.getComponentName()).thenReturn("r1");
        registry.registerWebComponent(r1);

        MappingRegistry mappingRegistry = new MappingRegistry();
        webContext.registerWebComponent(mappingRegistry);

        class Controller {
            public String home() { return "home"; }
            @ResponseBody
            public String api() { return "api"; }
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
    }
}

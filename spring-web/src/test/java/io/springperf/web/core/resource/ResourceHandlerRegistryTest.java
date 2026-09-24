package io.springperf.web.core.resource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.mapping.MappingRegistry;

class ResourceHandlerRegistryTest {

    private WebContext webContext;
    private MappingRegistry mappingRegistry;

    @BeforeEach
    void setUp() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.CONTEXT_PATH, "/")).thenReturn("/");
        webContext = new WebContext(mock(DispatcherHandler.class), props);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(any())).thenReturn(Collections.emptyMap());
        webContext.setCtx(ctx);
        mappingRegistry = new MappingRegistry();
        webContext.registerWebComponent(mappingRegistry);
    }

    @Test
    void initWithWebContext_registersDefaultMappingRegistry() {
        ResourceHandlerRegistry registry = new ResourceHandlerRegistry();
        registry.initWithWebContext(webContext);
        assertNotNull(webContext.getWebComponent(MappingRegistry.class));
    }

    @Test
    void initComponentPhase2_withRegistrations_registersMappings() throws Exception {
        ResourceHandlerRegistry registry = new ResourceHandlerRegistry();
        registry.registerWebComponent(
                new ResourceHandlerRegistration("/static/**").addResourceLocations("classpath:/static/"));
        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();

        // 每个 pathPattern 应注册一个 mapping context
        assertFalse(mappingRegistry.getMappingContextList().isEmpty());
    }

    @Test
    void initComponentPhase2_multipleRegistrations_registersAllMappings() throws Exception {
        ResourceHandlerRegistry registry = new ResourceHandlerRegistry();
        registry.registerWebComponent(
                new ResourceHandlerRegistration("/static/**").addResourceLocations("classpath:/static/"));
        registry.registerWebComponent(
                new ResourceHandlerRegistration("/images/**", "/css/**").addResourceLocations("classpath:/images/"));
        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();

        assertEquals(3, mappingRegistry.getMappingContextList().size());
    }

    @Test
    void initComponentPhase2_noRegistrations_noMappings() throws Exception {
        ResourceHandlerRegistry registry = new ResourceHandlerRegistry();
        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();

        assertTrue(mappingRegistry.getMappingContextList().isEmpty());
    }

    // ==================== spring.mvc.static-path-pattern 全局前缀 ====================

    @Test
    void initComponentPhase2_withGlobalPrefix_prependsToPatterns() throws Exception {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.CONTEXT_PATH, "/")).thenReturn("/");
        when(props.get(PropertiesConstant.MVC_STATIC_PATH_PATTERN, PropertiesConstant.MVC_STATIC_PATH_PATTERN_DEFAULT))
                .thenReturn("/resources/**");
        WebContext ctx = new WebContext(mock(DispatcherHandler.class), props);
        ApplicationContext appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansOfType(any())).thenReturn(Collections.emptyMap());
        ctx.setCtx(appCtx);
        MappingRegistry mapping = new MappingRegistry();
        ctx.registerWebComponent(mapping);

        ResourceHandlerRegistry registry = new ResourceHandlerRegistry();
        registry.registerWebComponent(
                new ResourceHandlerRegistration("/static/**").addResourceLocations("classpath:/static/"));
        registry.initWithWebContext(ctx);
        registry.initComponentPhase1();
        registry.initComponentPhase2();

        assertEquals(1, mapping.getMappingContextList().size());
        assertEquals("/resources/static/**", mapping.getMappingContextList().get(0).getPathRule());
    }

    @Test
    void normalizePrefix_defaultAndEmpty_noPrefix() {
        assertEquals("", ResourceHandlerRegistration.normalizePrefix(null));
        assertEquals("", ResourceHandlerRegistration.normalizePrefix(""));
        assertEquals("", ResourceHandlerRegistration.normalizePrefix("  "));
        assertEquals("", ResourceHandlerRegistration.normalizePrefix("/**"));
        assertEquals("", ResourceHandlerRegistration.normalizePrefix("**"));
    }

    @Test
    void normalizePrefix_normalizesSlashes() {
        assertEquals("/resources", ResourceHandlerRegistration.normalizePrefix("/resources/**"));
        assertEquals("/resources", ResourceHandlerRegistration.normalizePrefix("/resources/"));
        assertEquals("/resources", ResourceHandlerRegistration.normalizePrefix("resources"));
        assertEquals("/a/b", ResourceHandlerRegistration.normalizePrefix("/a/b/**"));
    }

    @Test
    void buildPathMappingContext_withPrefix_combines() {
        ResourceHandlerRegistration reg = new ResourceHandlerRegistration("/static/**")
                .addResourceLocations("classpath:/static/");
        assertEquals("/resources/static/**", reg.buildPathMappingContext("/resources/**").get(0).getPathRule());
        assertEquals("/static/**", reg.buildPathMappingContext("/**").get(0).getPathRule());
        assertEquals("/static/**", reg.buildPathMappingContext(null).get(0).getPathRule());
    }

    // ==================== spring.web.resources.* 默认映射 ====================

    private WebContext ctxWithProps(ApplicationProperties props) {
        // 兜底桩：static-locations 与 cache 键未显式配置时走默认值
        lenient()
                .when(props.get(PropertiesConstant.WEB_RESOURCES_STATIC_LOCATIONS,
                        PropertiesConstant.WEB_RESOURCES_STATIC_LOCATIONS_DEFAULT))
                .thenReturn(PropertiesConstant.WEB_RESOURCES_STATIC_LOCATIONS_DEFAULT);
        lenient().when(props.get(PropertiesConstant.WEB_RESOURCES_CACHE_PERIOD, null)).thenReturn(null);
        lenient().when(props.get(PropertiesConstant.WEB_RESOURCES_CACHE_CONTROL_MAX_AGE, null)).thenReturn(null);
        WebContext ctx = new WebContext(mock(DispatcherHandler.class), props);
        ApplicationContext appCtx = mock(ApplicationContext.class);
        when(appCtx.getBeansOfType(any())).thenReturn(Collections.emptyMap());
        ctx.setCtx(appCtx);
        MappingRegistry mapping = new MappingRegistry();
        ctx.registerWebComponent(mapping);
        return ctx;
    }

    @Test
    void addMappings_enabled_noUserRegistrations_autoRegistersDefaultMapping() throws Exception {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.CONTEXT_PATH, "/")).thenReturn("/");
        when(props.getBoolean(PropertiesConstant.WEB_RESOURCES_ADD_MAPPINGS,
                PropertiesConstant.WEB_RESOURCES_ADD_MAPPINGS_DEFAULT)).thenReturn(true);

        WebContext ctx = ctxWithProps(props);
        ResourceHandlerRegistry registry = new ResourceHandlerRegistry();
        registry.initWithWebContext(ctx);
        registry.initComponentPhase1();
        registry.initComponentPhase2();

        MappingRegistry mapping = ctx.getWebComponent(MappingRegistry.class);
        assertEquals(1, mapping.getMappingContextList().size(), "应自动注册 /** 默认映射");
        assertEquals("/**", mapping.getMappingContextList().get(0).getPathRule());
    }

    @Test
    void addMappings_enabled_withUserRegistrations_coexists() throws Exception {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.CONTEXT_PATH, "/")).thenReturn("/");
        when(props.getBoolean(PropertiesConstant.WEB_RESOURCES_ADD_MAPPINGS,
                PropertiesConstant.WEB_RESOURCES_ADD_MAPPINGS_DEFAULT)).thenReturn(true);

        WebContext ctx = ctxWithProps(props);
        ResourceHandlerRegistry registry = new ResourceHandlerRegistry();
        registry.registerWebComponent(
                new ResourceHandlerRegistration("/static/**").addResourceLocations("classpath:/static/"));
        registry.initWithWebContext(ctx);
        registry.initComponentPhase1();
        registry.initComponentPhase2();

        MappingRegistry mapping = ctx.getWebComponent(MappingRegistry.class);
        // 对齐 Boot：默认 /** 映射与用户注册共存（特异性匹配下用户 pattern 优先）
        assertEquals(2, mapping.getMappingContextList().size());
        assertTrue(mapping.getMappingContextList().stream().anyMatch(m -> m.getPathRule().equals("/static/**")));
        assertTrue(mapping.getMappingContextList().stream().anyMatch(m -> m.getPathRule().equals("/**")));
    }

    @Test
    void addMappings_disabled_noAutoRegister() throws Exception {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.CONTEXT_PATH, "/")).thenReturn("/");
        // 默认 false：不自动注册

        WebContext ctx = ctxWithProps(props);
        ResourceHandlerRegistry registry = new ResourceHandlerRegistry();
        registry.initWithWebContext(ctx);
        registry.initComponentPhase1();
        registry.initComponentPhase2();

        MappingRegistry mapping = ctx.getWebComponent(MappingRegistry.class);
        assertTrue(mapping.getMappingContextList().isEmpty(), "add-mappings=false 不应注册默认映射");
    }

    @Test
    void addMappings_enabled_withCachePeriod_appliedToDefaultRegistration() throws Exception {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.CONTEXT_PATH, "/")).thenReturn("/");
        when(props.getBoolean(PropertiesConstant.WEB_RESOURCES_ADD_MAPPINGS,
                PropertiesConstant.WEB_RESOURCES_ADD_MAPPINGS_DEFAULT)).thenReturn(true);
        WebContext ctx = ctxWithProps(props);
        // 注意：置于 ctxWithProps 之后，确保覆盖其兜底桩（Mockito 后定义优先）
        when(props.get(PropertiesConstant.WEB_RESOURCES_CACHE_PERIOD, null)).thenReturn("1h");
        when(props.getDurationSeconds(PropertiesConstant.WEB_RESOURCES_CACHE_PERIOD, 0L)).thenReturn(3600L);
        ResourceHandlerRegistry registry = new ResourceHandlerRegistry();
        registry.initWithWebContext(ctx);
        registry.initComponentPhase1();
        registry.initComponentPhase2();

        MappingRegistry mapping = ctx.getWebComponent(MappingRegistry.class);
        assertEquals(1, mapping.getMappingContextList().size());
        ResourceRequestHandler handler = (ResourceRequestHandler) mapping.getMappingContextList().get(0).getBean();
        assertNotNull(handler);
        assertEquals(3600, handler.getRegistration().getCachePeriod(), "全局 cache.period 应应用到默认注册");
    }
}

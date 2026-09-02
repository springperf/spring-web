package io.springperf.web.core.resource;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.mapping.MappingRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        registry.registerWebComponent(new ResourceHandlerRegistration("/static/**")
                .addResourceLocations("classpath:/static/"));
        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();

        // 每个 pathPattern 应注册一个 mapping context
        assertFalse(mappingRegistry.getMappingContextList().isEmpty());
    }

    @Test
    void initComponentPhase2_multipleRegistrations_registersAllMappings() throws Exception {
        ResourceHandlerRegistry registry = new ResourceHandlerRegistry();
        registry.registerWebComponent(new ResourceHandlerRegistration("/static/**")
                .addResourceLocations("classpath:/static/"));
        registry.registerWebComponent(new ResourceHandlerRegistration("/images/**", "/css/**")
                .addResourceLocations("classpath:/images/"));
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
}

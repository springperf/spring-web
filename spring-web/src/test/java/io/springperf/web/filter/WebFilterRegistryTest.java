package io.springperf.web.filter;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.BaseWebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WebFilterRegistryTest {

    private WebContext createWebContext() {
        ApplicationProperties props =
                mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.CONTEXT_PATH, "/")).thenReturn("/");
        WebContext wc = new WebContext(mock(DispatcherHandler.class), props);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(any())).thenReturn(Collections.emptyMap());
        wc.setCtx(ctx);
        return wc;
    }

    @Test
    void constructor_doesNotThrow() {
        assertNotNull(new WebFilterRegistry());
    }

    @Test
    void doFilter_beforePhase3_emptyFilters_callsTerminal() throws Exception {
        WebFilterRegistry registry = new WebFilterRegistry();
        WebFilterRegistry.FilterChainTerminal terminal = mock(WebFilterRegistry.FilterChainTerminal.class);
        MappingResult mappingResult = MappingResult.notFound();

        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getRequestContext()).thenReturn(request);
        when(request.getFilterIndexAndIncrement()).thenReturn(0);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        registry.doFilter(request, response, mappingResult, terminal);
        verify(terminal).doFilter(request, response, mappingResult);
    }

    @Test
    void initPhase3_withNoFilters_callsTerminalDirectly() throws Exception {
        WebFilterRegistry.FilterChainTerminal terminal = mock(WebFilterRegistry.FilterChainTerminal.class);
        MappingResult mappingResult = MappingResult.notFound();
        WebContext webContext = createWebContext();
        WebFilterRegistry registry = new WebFilterRegistry();

        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();
        registry.initComponentPhase3();

        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getRequestContext()).thenReturn(request);
        when(request.getFilterIndexAndIncrement()).thenReturn(0);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        registry.doFilter(request, response, mappingResult, terminal);
        verify(terminal).doFilter(request, response, mappingResult);
    }

    @Test
    void initPhase3_withOneFilter_runsFilterThenTerminal() throws Exception {
        WebFilterRegistry.FilterChainTerminal terminal = mock(WebFilterRegistry.FilterChainTerminal.class);
        MappingResult mappingResult = MappingResult.notFound();
        WebContext webContext = createWebContext();
        WebFilterRegistry registry = new WebFilterRegistry();

        WebFilter filter = mock(WebFilter.class);
        when(filter.getComponentName()).thenReturn("testFilter");
        registry.registerWebComponent(new WebFilterRegistration(filter));

        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();
        registry.initComponentPhase3();

        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getRequestContext()).thenReturn(request);
        when(request.getFilterIndexAndIncrement()).thenReturn(0, 1);
        when(request.getPath()).thenReturn("/test");
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        registry.doFilter(request, response, mappingResult, terminal);
        verify(filter).doFilter(eq(request), eq(response), any(DefaultFilterChain.class));
    }

    @Test
    void initPhase3_withMultipleFilters_runsAllInOrder() throws Exception {
        WebContext webContext = createWebContext();
        WebFilterRegistry registry = new WebFilterRegistry();

        WebFilter filter1 = mock(WebFilter.class);
        when(filter1.getComponentName()).thenReturn("filter1");
        WebFilter filter2 = mock(WebFilter.class);
        when(filter2.getComponentName()).thenReturn("filter2");
        registry.registerWebComponent(new WebFilterRegistration(filter1));
        registry.registerWebComponent(new WebFilterRegistration(filter2));

        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();
        registry.initComponentPhase3();

        assertEquals(2, registry.registrations.size());
        assertSame(filter1, registry.registrations.get(0).getFilter());
        assertSame(filter2, registry.registrations.get(1).getFilter());
    }

    @Test
    void resolveFilters_cachedHit_returnsDirectly() throws Exception {
        WebFilterRegistry registry = new WebFilterRegistry();
        WebFilter filter = mock(WebFilter.class);
        when(filter.getComponentName()).thenReturn("testFilter");
        List<WebFilter> cached = Collections.singletonList(filter);

        PathMappingContext mappingContext = mock(PathMappingContext.class);
        when(mappingContext.getCachedFilters()).thenReturn(cached);

        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getPath()).thenReturn("/test");

        List<WebFilter> resolved = registry.resolveFilters(MappingResult.matched(mappingContext), request);
        assertSame(cached, resolved);
    }

    @Test
    void resolveFilters_emptyCache_recomputesAndCaches() throws Exception {
        WebContext webContext = createWebContext();
        WebFilterRegistry registry = new WebFilterRegistry();
        WebFilter filter = mock(WebFilter.class);
        when(filter.getComponentName()).thenReturn("testFilter");
        registry.registerWebComponent(new WebFilterRegistration(filter));

        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();
        registry.initComponentPhase3();

        PathMappingContext mappingContext = mock(PathMappingContext.class);
        when(mappingContext.getCachedFilters()).thenReturn(null);
        when(mappingContext.getPathRule()).thenReturn("/test");

        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getPath()).thenReturn("/test");

        List<WebFilter> resolved = registry.resolveFilters(MappingResult.matched(mappingContext), request);
        assertFalse(resolved.isEmpty());
        assertSame(filter, resolved.get(0));
        verify(mappingContext).setCachedFilters(any());
    }

    @Test
    void getWebFilter_afterPhase3_returnsSortedFilters() throws Exception {
        WebContext webContext = createWebContext();
        WebFilterRegistry registry = new WebFilterRegistry();

        WebFilter lowPriority = mock(WebFilter.class);
        when(lowPriority.getComponentName()).thenReturn("low");
        WebFilter highPriority = mock(WebFilter.class);
        when(highPriority.getComponentName()).thenReturn("high");

        registry.registerWebComponent(new WebFilterRegistration(lowPriority).order(100));
        registry.registerWebComponent(new WebFilterRegistration(highPriority).order(10));

        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();
        registry.initComponentPhase3();

        assertEquals(2, registry.registrations.size());
        assertSame(highPriority, registry.registrations.get(0).getFilter());
        assertSame(lowPriority, registry.registrations.get(1).getFilter());
    }
}

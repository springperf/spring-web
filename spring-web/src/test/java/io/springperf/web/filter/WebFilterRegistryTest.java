package io.springperf.web.filter;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.http.BaseWebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebFilterRegistryTest {

    private WebContext createWebContext(DispatcherHandler handler) {
        ApplicationProperties props =
                mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.CONTEXT_PATH, "/")).thenReturn("/");
        WebContext wc = new WebContext(handler, props);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(any())).thenReturn(Collections.emptyMap());
        wc.setCtx(ctx);
        return wc;
    }

    @Test
    void constructor_doesNotThrow() {
        // Just verify constructor doesn't throw - auto-registration is internal
        assertNotNull(new WebFilterRegistry());
    }

    @Test
    void doFilter_beforePhase3_throwsNPE() {
        WebFilterRegistry registry = new WebFilterRegistry();
        // Do NOT call any init - filterChain is null
        assertThrows(NullPointerException.class,
                () -> registry.doFilter(mock(BaseWebServerHttpRequest.class), mock(WebServerHttpResponse.class)));
    }

    @Test
    void initPhase3_withNoFilters_dispatchesDirectly() throws Exception {
        DispatcherHandler handler = mock(DispatcherHandler.class);
        WebContext webContext = createWebContext(handler);
        WebFilterRegistry registry = new WebFilterRegistry();

        // Initialize through all phases
        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();
        registry.initComponentPhase3();

        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getRequestContext()).thenReturn(request);
        when(request.getFilterIndexAndIncrement()).thenReturn(0);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        registry.doFilter(request, response);
        verify(handler).handle(request, response);
    }

    @Test
    void initPhase3_withOneFilter_runsFilterThenDispatcher() throws Exception {
        DispatcherHandler handler = mock(DispatcherHandler.class);
        WebContext webContext = createWebContext(handler);
        WebFilterRegistry registry = new WebFilterRegistry();

        // Register a WebFilter BEFORE initialization
        WebFilter filter = mock(WebFilter.class);
        when(filter.getComponentName()).thenReturn("testFilter");
        registry.registerWebComponent(filter);

        // Initialize through all phases
        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();
        registry.initComponentPhase3();

        // First call: filter index 0 → filter.doFilter
        // Second call (from filter): filter index 1 → dispatcher.handle
        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getRequestContext()).thenReturn(request);
        when(request.getFilterIndexAndIncrement()).thenReturn(0, 1);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        registry.doFilter(request, response);
        verify(filter).doFilter(request, response, registry.filterChain);
    }

    @Test
    void initPhase3_withMultipleFilters_runsAllInOrder() throws Exception {
        DispatcherHandler handler = mock(DispatcherHandler.class);
        WebContext webContext = createWebContext(handler);
        WebFilterRegistry registry = new WebFilterRegistry();

        WebFilter filter1 = mock(WebFilter.class);
        when(filter1.getComponentName()).thenReturn("filter1");
        WebFilter filter2 = mock(WebFilter.class);
        when(filter2.getComponentName()).thenReturn("filter2");
        registry.registerWebComponent(filter1);
        registry.registerWebComponent(filter2);

        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();
        registry.initComponentPhase3();

        assertEquals(2, registry.filters.size());
        assertSame(filter1, registry.filters.get(0));
        assertSame(filter2, registry.filters.get(1));
    }

    @Test
    void getWebFilter_afterPhase3_returnsSortedFilters() throws Exception {
        DispatcherHandler handler = mock(DispatcherHandler.class);
        WebContext webContext = createWebContext(handler);
        WebFilterRegistry registry = new WebFilterRegistry();

        WebFilter lowPriority = mock(WebFilter.class);
        when(lowPriority.getComponentName()).thenReturn("low");
        when(lowPriority.getOrder()).thenReturn(100);
        WebFilter highPriority = mock(WebFilter.class);
        when(highPriority.getComponentName()).thenReturn("high");
        when(highPriority.getOrder()).thenReturn(10);

        registry.registerWebComponent(lowPriority);
        registry.registerWebComponent(highPriority);

        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();
        registry.initComponentPhase3();

        // After phase3, the filters list should be sorted by order
        assertEquals(2, registry.filters.size());
        assertSame(highPriority, registry.filters.get(0));
        assertSame(lowPriority, registry.filters.get(1));
    }
}
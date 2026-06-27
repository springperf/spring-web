package io.springperf.web.core.filter;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.BaseWebServerHttpRequest;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private BaseWebServerHttpRequest createMockRequest() {
        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getRequestContext()).thenReturn(request);
        Map attrs = new HashMap<>();
        Map<RequestAttribute<?>, Object> fastAttrs = new HashMap<>();
        when(request.getAttribute(anyString())).thenAnswer(inv -> attrs.get(inv.getArgument(0)));
        doAnswer(inv -> { attrs.put(inv.getArgument(0), inv.getArgument(1)); return null; }).when(request).setAttribute(anyString(), any());
        when(request.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> fastAttrs.get(inv.getArgument(0)));
        doAnswer(inv -> { fastAttrs.put(inv.getArgument(0), inv.getArgument(1)); return null; }).when(request).setAttribute(any(RequestAttribute.class), any());
        when(request.getFilterIndexAndIncrement()).thenReturn(0);
        return request;
    }

    @Test
    void constructor_doesNotThrow() {
        assertNotNull(new WebFilterRegistry(mock(DispatcherHandler.class)));
    }

    @Test
    void doFilter_beforePhase3_noFilters_callHandleAfterFilter() throws Exception {
        DispatcherHandler dh = mock(DispatcherHandler.class);
        WebFilterRegistry registry = new WebFilterRegistry(dh);
        MappingResult mappingResult = MappingResult.notFound();

        BaseWebServerHttpRequest request = createMockRequest();
        when(request.getPath()).thenReturn("/test");
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        MappingResult.set(request, mappingResult);

        registry.doFilter(request, response);
        verify(dh).handleAfterFilter(request, response, mappingResult);
    }

    @Test
    void initPhase3_withNoFilters_callHandleAfterFilterDirectly() throws Exception {
        WebContext webContext = createWebContext();
        DispatcherHandler dh = mock(DispatcherHandler.class);
        WebFilterRegistry registry = new WebFilterRegistry(dh);

        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();
        registry.initComponentPhase3();

        MappingResult mappingResult = MappingResult.notFound();

        BaseWebServerHttpRequest request = createMockRequest();
        when(request.getPath()).thenReturn("/test");
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        MappingResult.set(request, mappingResult);

        registry.doFilter(request, response);
        verify(dh).handleAfterFilter(request, response, mappingResult);
    }

    @Test
    void initPhase3_withOneFilter_runsFilterThenHandleAfterFilter() throws Exception {
        WebContext webContext = createWebContext();
        DispatcherHandler dh = mock(DispatcherHandler.class);
        WebFilterRegistry registry = new WebFilterRegistry(dh);

        WebFilter filter = mock(WebFilter.class);
        when(filter.getComponentName()).thenReturn("testFilter");
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(filter).doFilter(any(), any(), any());
        registry.registerWebComponent(new WebFilterRegistration(filter));

        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();
        registry.initComponentPhase3();

        BaseWebServerHttpRequest request = createMockRequest();
        when(request.getFilterIndexAndIncrement()).thenReturn(0, 1);
        when(request.getPath()).thenReturn("/test");
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        MappingResult mr = MappingResult.notFound();
        MappingResult.set(request, mr);

        registry.doFilter(request, response);
        verify(filter).doFilter(eq(request), eq(response), any(DefaultFilterChain.class));
        verify(dh).handleAfterFilter(request, response, mr);
    }

    @Test
    void initPhase3_withMultipleFilters_runsAllInOrder() throws Exception {
        WebContext webContext = createWebContext();
        WebFilterRegistry registry = new WebFilterRegistry(mock(DispatcherHandler.class));

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
    void resolveFilterChain_cachedHit_returnsDirectly() throws Exception {
        DispatcherHandler dh = mock(DispatcherHandler.class);
        WebFilterRegistry registry = new WebFilterRegistry(dh);
        DefaultFilterChain cached = new DefaultFilterChain(mock(DispatcherHandler.class), Collections.emptyList());

        PathMappingContext mappingContext = mock(PathMappingContext.class);
        when(mappingContext.getCachedFilterChain()).thenReturn(cached);

        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getPath()).thenReturn("/test");
        when(request.getRequestContext()).thenReturn(request);
        Map<RequestAttribute<?>, Object> fastAttrs = new HashMap<>();
        when(request.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> fastAttrs.get(inv.getArgument(0)));
        doAnswer(inv -> { fastAttrs.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(request).setAttribute(any(RequestAttribute.class), any());
        MappingResult.set(request, MappingResult.matched(mappingContext));

        DefaultFilterChain resolved = registry.resolveFilterChain(request);
        assertSame(cached, resolved);
    }

    @Test
    void resolveFilterChain_emptyCache_recomputesAndCaches() throws Exception {
        WebContext webContext = createWebContext();
        DispatcherHandler dh = mock(DispatcherHandler.class);
        WebFilterRegistry registry = new WebFilterRegistry(dh);
        WebFilter filter = mock(WebFilter.class);
        when(filter.getComponentName()).thenReturn("testFilter");
        registry.registerWebComponent(new WebFilterRegistration(filter));

        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();
        registry.initComponentPhase3();

        PathMappingContext mappingContext = mock(PathMappingContext.class);
        when(mappingContext.getCachedFilterChain()).thenReturn(null);
        when(mappingContext.getPathRule()).thenReturn("/test");

        BaseWebServerHttpRequest request = mock(BaseWebServerHttpRequest.class);
        when(request.getPath()).thenReturn("/test");
        when(request.getRequestContext()).thenReturn(request);
        Map<RequestAttribute<?>, Object> fastAttrs = new HashMap<>();
        when(request.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> fastAttrs.get(inv.getArgument(0)));
        doAnswer(inv -> { fastAttrs.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(request).setAttribute(any(RequestAttribute.class), any());
        MappingResult.set(request, MappingResult.matched(mappingContext));

        DefaultFilterChain resolved = registry.resolveFilterChain(request);
        assertNotNull(resolved);
        verify(mappingContext).setCachedFilterChain(any());
    }

    @Test
    void getWebFilter_afterPhase3_returnsSortedFilters() throws Exception {
        WebContext webContext = createWebContext();
        WebFilterRegistry registry = new WebFilterRegistry(mock(DispatcherHandler.class));

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

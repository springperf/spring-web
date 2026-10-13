package io.springperf.web.core;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.ArgumentResolverRegistry;
import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.core.cors.CorsRegistry;
import io.springperf.web.core.exception.ExceptionRegistry;
import io.springperf.web.core.filter.WebFilterRegistry;
import io.springperf.web.core.interceptor.InterceptorRegistry;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.metrics.WebMetrics;
import io.springperf.web.core.pool.BizPoolRegistry;
import io.springperf.web.core.retval.ReturnValueResolverRegistry;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.support.ServletRequestHandledEvent;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 {@code spring.mvc.publish-request-handled-events}：请求完成后发布
 * {@link ServletRequestHandledEvent}。
 */
class DispatcherHandlerRequestHandledEventTest {

    private WebServerHttpRequest createRequest(String method, String uri) {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        RequestContext reqCtx = mock(RequestContext.class);
        when(req.getRequestContext()).thenReturn(reqCtx);
        Map<RequestAttribute<?>, Object> fastAttrs = new HashMap<>();
        lenient().when(reqCtx.getAttribute(any(RequestAttribute.class)))
                .thenAnswer(inv -> fastAttrs.get(inv.getArgument(0)));
        lenient().doAnswer(inv -> { fastAttrs.put(inv.getArgument(0), inv.getArgument(1)); return null; })
                .when(reqCtx).setAttribute(any(RequestAttribute.class), any());
        lenient().when(req.getMethodValue()).thenReturn(method);
        lenient().when(req.getUriStr()).thenReturn(uri);
        lenient().when(req.getHeaders()).thenReturn(new org.springframework.http.HttpHeaders());
        return req;
    }

    private DispatcherHandler buildHandler(boolean publish, ApplicationEventPublisher publisher) throws Exception {
        DispatcherHandler handler = new DispatcherHandler();
        WebContext webContext = mock(WebContext.class);
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(webContext.getProps()).thenReturn(props);
        when(webContext.getCtx()).thenReturn((org.springframework.context.ApplicationContext) null);
        lenient().when(props.getBoolean(PropertiesConstant.MVC_PUBLISH_REQUEST_HANDLED_EVENTS,
                PropertiesConstant.MVC_PUBLISH_REQUEST_HANDLED_EVENTS_DEFAULT)).thenReturn(publish);

        when(webContext.getWebComponentWithDefault(eq(MappingRegistry.class), any(MappingRegistry.class)))
                .thenReturn(mock(MappingRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(ExceptionRegistry.class), any(ExceptionRegistry.class)))
                .thenReturn(mock(ExceptionRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(ArgumentResolverRegistry.class), any(ArgumentResolverRegistry.class)))
                .thenReturn(mock(ArgumentResolverRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(ReturnValueResolverRegistry.class), any(ReturnValueResolverRegistry.class)))
                .thenReturn(mock(ReturnValueResolverRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(CorsRegistry.class), any(CorsRegistry.class)))
                .thenReturn(mock(CorsRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(InterceptorRegistry.class), any(InterceptorRegistry.class)))
                .thenReturn(mock(InterceptorRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(BizPoolRegistry.class), any(BizPoolRegistry.class)))
                .thenReturn(mock(BizPoolRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(AsyncSupportRegistry.class), any(AsyncSupportRegistry.class)))
                .thenReturn(mock(AsyncSupportRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(WebFilterRegistry.class), any(WebFilterRegistry.class)))
                .thenReturn(mock(WebFilterRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(WebMetrics.class), any()))
                .thenReturn(mock(WebMetrics.class));

        handler.initWithWebContext(webContext);
        // 直接注入发布器（生产路径由 WebContext.getCtx() 解析，mock 场景显式设置）
        if (publisher != null) {
            java.lang.reflect.Field f = DispatcherHandler.class.getDeclaredField("eventPublisher");
            f.setAccessible(true);
            f.set(handler, publisher);
        }
        return handler;
    }

    @Test
    void publish_enabled_publishesServletRequestHandledEvent() throws Exception {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        DispatcherHandler handler = buildHandler(true, publisher);
        WebServerHttpRequest req = createRequest("GET", "/api/hello");
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getStatus()).thenReturn(HttpStatus.OK);

        MappingResult matched = MappingResult.notFound();
        handler.handleAfterFilter(req, resp, matched);

        ArgumentCaptor<org.springframework.context.ApplicationEvent> captor =
                ArgumentCaptor.forClass(org.springframework.context.ApplicationEvent.class);
        verify(publisher).publishEvent(captor.capture());
        ServletRequestHandledEvent event = (ServletRequestHandledEvent) captor.getValue();
        assertEquals("GET", event.getMethod());
        assertEquals("/api/hello", event.getRequestUrl());
        assertEquals(200, event.getStatusCode());
    }

    @Test
    void publish_disabled_doesNotPublish() throws Exception {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        DispatcherHandler handler = buildHandler(false, publisher);
        WebServerHttpRequest req = createRequest("GET", "/api/hello");
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getStatus()).thenReturn(HttpStatus.OK);

        handler.handleAfterFilter(req, resp, MappingResult.notFound());

        verify(publisher, never()).publishEvent(any(org.springframework.context.ApplicationEvent.class));
    }

    @Test
    void publish_noPublisher_doesNotThrow() throws Exception {
        DispatcherHandler handler = buildHandler(true, null);
        WebServerHttpRequest req = createRequest("GET", "/api/hello");
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getStatus()).thenReturn(HttpStatus.OK);

        // 无发布器时应静默跳过，不抛异常
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> handler.handleAfterFilter(req, resp, MappingResult.notFound()));
    }
}

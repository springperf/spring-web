package io.springperf.web.core;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

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

/**
 * 覆盖 {@code spring.mvc.dispatch.error/options/trace} 三开关的分发行为。
 */
class DispatcherHandlerDispatchTest {

    private WebServerHttpRequest createRequest(String method) {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        RequestContext reqCtx = mock(RequestContext.class);
        when(req.getRequestContext()).thenReturn(reqCtx);
        Map<RequestAttribute<?>, Object> fastAttrs = new HashMap<>();
        when(reqCtx.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> fastAttrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            fastAttrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(reqCtx).setAttribute(any(RequestAttribute.class), any());
        lenient().when(req.getMethodValue()).thenReturn(method);
        lenient().when(req.getHeaders()).thenReturn(new org.springframework.http.HttpHeaders());
        return req;
    }

    /** 构造 DispatcherHandler，三开关按传入值配置。 */
    private DispatcherHandler buildHandler(boolean error, boolean options, boolean trace) throws Exception {
        DispatcherHandler handler = new DispatcherHandler();
        WebContext webContext = mock(WebContext.class);
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(webContext.getProps()).thenReturn(props);
        when(props.getBoolean(PropertiesConstant.MVC_DISPATCH_ERROR, PropertiesConstant.MVC_DISPATCH_ERROR_DEFAULT))
                .thenReturn(error);
        when(props.getBoolean(PropertiesConstant.MVC_DISPATCH_OPTIONS, PropertiesConstant.MVC_DISPATCH_OPTIONS_DEFAULT))
                .thenReturn(options);
        when(props.getBoolean(PropertiesConstant.MVC_DISPATCH_TRACE, PropertiesConstant.MVC_DISPATCH_TRACE_DEFAULT))
                .thenReturn(trace);

        MappingRegistry mappingRegistry = mock(MappingRegistry.class);
        ExceptionRegistry exceptionRegistry = mock(ExceptionRegistry.class);
        CorsRegistry corsRegistry = mock(CorsRegistry.class);
        InterceptorRegistry interceptorRegistry = mock(InterceptorRegistry.class);
        WebFilterRegistry webFilterRegistry = mock(WebFilterRegistry.class);
        when(webContext.getWebComponentWithDefault(eq(MappingRegistry.class), any(MappingRegistry.class)))
                .thenReturn(mappingRegistry);
        when(webContext.getWebComponentWithDefault(eq(ExceptionRegistry.class), any(ExceptionRegistry.class)))
                .thenReturn(exceptionRegistry);
        when(webContext.getWebComponentWithDefault(eq(ArgumentResolverRegistry.class),
                any(ArgumentResolverRegistry.class))).thenReturn(mock(ArgumentResolverRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(ReturnValueResolverRegistry.class),
                any(ReturnValueResolverRegistry.class))).thenReturn(mock(ReturnValueResolverRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(CorsRegistry.class), any(CorsRegistry.class)))
                .thenReturn(corsRegistry);
        when(webContext.getWebComponentWithDefault(eq(InterceptorRegistry.class), any(InterceptorRegistry.class)))
                .thenReturn(interceptorRegistry);
        when(webContext.getWebComponentWithDefault(eq(BizPoolRegistry.class), any(BizPoolRegistry.class)))
                .thenReturn(mock(BizPoolRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(AsyncSupportRegistry.class), any(AsyncSupportRegistry.class)))
                .thenReturn(mock(AsyncSupportRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(WebFilterRegistry.class), any(WebFilterRegistry.class)))
                .thenReturn(webFilterRegistry);
        when(webContext.getWebComponentWithDefault(eq(WebMetrics.class), any())).thenReturn(mock(WebMetrics.class));

        doAnswer(invocation -> {
            WebServerHttpRequest req = invocation.getArgument(0);
            WebServerHttpResponse resp = invocation.getArgument(1);
            MappingResult mr = MappingResult.get(req);
            if (mr != null) {
                handler.handleAfterFilter(req, resp, mr);
            }
            return null;
        }).when(webFilterRegistry).doFilter(any(), any());

        handler.initWithWebContext(webContext);
        return handler;
    }

    @Test
    void dispatchTrace_disabled_skipsMapping() throws Exception {
        DispatcherHandler handler = buildHandler(true, true, false);
        WebServerHttpRequest req = createRequest("TRACE");
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getStatus()).thenReturn(HttpStatus.NOT_FOUND);

        handler.handle(req, resp);

        // 关闭 TRACE：不进入 mapping，直接按 notFound 处理
        verify(handler.mappingRegistryForTest(), never()).mapping(any());
    }

    @Test
    void dispatchTrace_enabled_goesToMapping() throws Exception {
        DispatcherHandler handler = buildHandler(true, true, true);
        WebServerHttpRequest req = createRequest("TRACE");
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        MappingResult notFound = MappingResult.notFound();
        MappingResult.set(req, notFound);
        when(handler.mappingRegistryForTest().mapping(req)).thenReturn(notFound);
        when(resp.getStatus()).thenReturn(HttpStatus.NOT_FOUND);

        handler.handle(req, resp);

        verify(handler.mappingRegistryForTest()).mapping(req);
    }

    @Test
    void dispatchTrace_disabled_lowercaseMethod_stillGated() throws Exception {
        // 方法 token 不保证大写（Netty 对未知方法原样保留原始串），
        // 闸门必须规范化后匹配，防止小写 "trace" 绕过 dispatch.trace=false
        DispatcherHandler handler = buildHandler(true, true, false);
        WebServerHttpRequest req = createRequest("trace");
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getStatus()).thenReturn(HttpStatus.NOT_FOUND);

        handler.handle(req, resp);

        verify(handler.mappingRegistryForTest(), never()).mapping(any());
    }

    @Test
    void dispatchError_disabled_skipsMapping() throws Exception {
        DispatcherHandler handler = buildHandler(false, true, true);
        WebServerHttpRequest req = createRequest("ERROR");
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getStatus()).thenReturn(HttpStatus.NOT_FOUND);

        handler.handle(req, resp);

        verify(handler.mappingRegistryForTest(), never()).mapping(any());
    }

    @Test
    void dispatchOptions_disabled_nonPreflight_skipsMapping() throws Exception {
        DispatcherHandler handler = buildHandler(true, false, true);
        WebServerHttpRequest req = createRequest("OPTIONS");
        // 非预检：无 CORS 头
        when(req.getMethod()).thenReturn(org.springframework.http.HttpMethod.OPTIONS);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getStatus()).thenReturn(HttpStatus.NOT_FOUND);

        handler.handle(req, resp);

        verify(handler.mappingRegistryForTest(), never()).mapping(any());
    }

    @Test
    void dispatchOptions_disabled_preflight_stillGoesToMapping() throws Exception {
        // 预检请求：即使关闭 OPTIONS 分发，仍需进入 mapping（放行），由 CORS 预检分支处理
        DispatcherHandler handler = buildHandler(true, false, true);
        WebServerHttpRequest req = createRequest("OPTIONS");
        when(req.getMethod()).thenReturn(org.springframework.http.HttpMethod.OPTIONS);
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set("Origin", "https://example.com");
        headers.set("Access-Control-Request-Method", "GET");
        when(req.getHeaders()).thenReturn(headers);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getStatus()).thenReturn(HttpStatus.OK);
        MappingResult notFound = MappingResult.notFound();
        MappingResult.set(req, notFound);
        when(handler.mappingRegistryForTest().mapping(req)).thenReturn(notFound);

        handler.handle(req, resp);

        // 预检放行 → mapping 被调用（未短路）
        verify(handler.mappingRegistryForTest()).mapping(req);
    }

    @Test
    void dispatchDefaults_allEnabled_goesToMapping() throws Exception {
        DispatcherHandler handler = buildHandler(true, true, true);
        WebServerHttpRequest req = createRequest("GET");
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        MappingResult notFound = MappingResult.notFound();
        MappingResult.set(req, notFound);
        when(handler.mappingRegistryForTest().mapping(req)).thenReturn(notFound);
        when(resp.getStatus()).thenReturn(HttpStatus.NOT_FOUND);

        handler.handle(req, resp);

        verify(handler.mappingRegistryForTest()).mapping(req);
    }
}

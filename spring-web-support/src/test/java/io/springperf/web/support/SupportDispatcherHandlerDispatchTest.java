package io.springperf.web.support;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.ArgumentResolverRegistry;
import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.core.cors.CorsRegistry;
import io.springperf.web.core.exception.ExceptionRegistry;
import io.springperf.web.core.filter.WebFilterRegistry;
import io.springperf.web.core.interceptor.InterceptorRegistry;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.metrics.WebMetrics;
import io.springperf.web.core.pool.BizPoolRegistry;
import io.springperf.web.core.retval.ReturnValueResolverRegistry;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupportDispatcherHandlerDispatchTest {

    @Mock WebContext webContext;
    @Mock MappingRegistry mappingRegistry;
    @Mock ExceptionRegistry exceptionRegistry;
    @Mock ArgumentResolverRegistry argumentResolverRegistry;
    @Mock ReturnValueResolverRegistry returnValueResolverRegistry;
    @Mock CorsRegistry corsRegistry;
    @Mock InterceptorRegistry interceptorRegistry;
    @Mock BizPoolRegistry bizPoolRegistry;
    @Mock AsyncSupportRegistry asyncSupportRegistry;
    @Mock WebFilterRegistry webFilterRegistry;
    @Mock WebMetrics metrics;
    @Mock WebServerHttpRequest req;
    @Mock WebServerHttpResponse resp;
    @Mock RequestContext requestContext;
    @Mock PathMappingContext pathContext;

    private SupportDispatcherHandler handler;

    @SuppressWarnings({"rawtypes", "unchecked"})
    @BeforeEach
    void setUp() {
        lenient().when(webContext.getWebComponentWithDefault(eq(MappingRegistry.class), any(MappingRegistry.class)))
                .thenReturn(mappingRegistry);
        lenient().when(webContext.getWebComponentWithDefault(eq(ExceptionRegistry.class), any(ExceptionRegistry.class)))
                .thenReturn(exceptionRegistry);
        lenient().when(webContext.getWebComponentWithDefault(eq(ArgumentResolverRegistry.class), any(ArgumentResolverRegistry.class)))
                .thenReturn(argumentResolverRegistry);
        lenient().when(webContext.getWebComponentWithDefault(eq(ReturnValueResolverRegistry.class), any(ReturnValueResolverRegistry.class)))
                .thenReturn(returnValueResolverRegistry);
        lenient().when(webContext.getWebComponentWithDefault(eq(CorsRegistry.class), any(CorsRegistry.class)))
                .thenReturn(corsRegistry);
        lenient().when(webContext.getWebComponentWithDefault(eq(InterceptorRegistry.class), any(InterceptorRegistry.class)))
                .thenReturn(interceptorRegistry);
        lenient().when(webContext.getWebComponentWithDefault(eq(BizPoolRegistry.class), any(BizPoolRegistry.class)))
                .thenReturn(bizPoolRegistry);
        lenient().when(webContext.getWebComponentWithDefault(eq(AsyncSupportRegistry.class), any(AsyncSupportRegistry.class)))
                .thenReturn(asyncSupportRegistry);
        lenient().when(webContext.getWebComponentWithDefault(eq(WebFilterRegistry.class), any(WebFilterRegistry.class)))
                .thenReturn(webFilterRegistry);
        lenient().when(webContext.getWebComponentWithDefault(eq(WebMetrics.class), any()))
                .thenReturn(metrics);

        handler = new SupportDispatcherHandler();
        handler.initWithWebContext(webContext);

        lenient().when(req.getRequestContext()).thenReturn(requestContext);
        lenient().when(req.getMethodValue()).thenReturn("GET");
        lenient().when(req.getURI()).thenReturn(java.net.URI.create("http://localhost/original"));
        lenient().when(resp.getStatus()).thenReturn(HttpStatus.OK);
    }

    private void resetThreadLocals() {
        RequestContextHolder.resetRequestAttributes();
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void getOrder_usesLowestPrecedenceMinusOffset() {
        assertTrue(handler.getOrder() < Integer.MAX_VALUE);
    }

    @Test
    void forward_notFoundPath_handlesNoFullMatchAndRestoresContext() throws Exception {
        resetThreadLocals();
        when(mappingRegistry.mapping(any())).thenReturn(MappingResult.notFound());

        handler.forward(req, resp, "/missing");

        verify(exceptionRegistry).handle(any(), any(), any());
        verify(interceptorRegistry).afterCompletion(any(), any(), any());
        assertNull(RequestContextHolder.getRequestAttributes(), "forward 后应清理 RequestContextHolder");
        assertNull(LocaleContextHolder.getLocaleContext(), "forward 后应清理 LocaleContextHolder");
    }

    @Test
    void forward_matchedPath_dispatchesThroughDoHandle() throws Exception {
        resetThreadLocals();
        MappingResult matched = MappingResult.matched(pathContext);
        when(mappingRegistry.mapping(any())).thenReturn(matched);
        when(corsRegistry.corsHandle(any(), any())).thenReturn(false);
        when(interceptorRegistry.preHandle(any(), any())).thenReturn(true);
        when(argumentResolverRegistry.resolveArguments(any(), any(), any())).thenReturn(new Object[0]);
        when(pathContext.getPathRule()).thenReturn("/target");

        handler.forward(req, resp, "/target");

        verify(returnValueResolverRegistry).resolveReturnValue(any(), eq(pathContext), any(), any());
        verify(interceptorRegistry).postHandle(any(), any(), any());
        verify(interceptorRegistry).afterCompletion(any(), any(), any());
        assertNull(RequestContextHolder.getRequestAttributes());
    }

    @Test
    void forward_restoresPreExistingContextHolders() throws Exception {
        ServletRequestAttributes savedAttrs = new ServletRequestAttributes(
                new io.springperf.web.support.servlet.PerfHttpServletRequest(req),
                new io.springperf.web.support.servlet.PerfHttpServletResponse(resp));
        RequestContextHolder.setRequestAttributes(savedAttrs);
        LocaleContextHolder.setLocaleContext(new org.springframework.context.i18n.SimpleLocaleContext(java.util.Locale.US));
        when(mappingRegistry.mapping(any())).thenReturn(MappingResult.notFound());

        handler.forward(req, resp, "/missing");

        assertSame(savedAttrs, RequestContextHolder.getRequestAttributes(), "forward 应恢复调用前的 RequestAttributes");
        assertEquals(java.util.Locale.US, LocaleContextHolder.getLocale(), "forward 应恢复调用前的 LocaleContext");
    }

    @Test
    void include_notFoundPath_wrapsResponseAndHandlesNoFullMatch() throws Exception {
        resetThreadLocals();
        when(mappingRegistry.mapping(any())).thenReturn(MappingResult.notFound());

        handler.include(req, resp, "/fragment");

        verify(exceptionRegistry).handle(any(), any(), any());
        assertNull(RequestContextHolder.getRequestAttributes());
    }

    @Test
    void include_matchedPath_invokesTargetAndKeepsOriginalStatus() throws Throwable {
        resetThreadLocals();
        MappingResult matched = MappingResult.matched(pathContext);
        when(mappingRegistry.mapping(any())).thenReturn(matched);
        when(corsRegistry.corsHandle(any(), any())).thenReturn(false);
        when(interceptorRegistry.preHandle(any(), any())).thenReturn(true);
        when(argumentResolverRegistry.resolveArguments(any(), any(), any())).thenReturn(new Object[0]);
        when(pathContext.getPathRule()).thenReturn("/fragment");
        when(pathContext.invoke(any(), any(), any())).thenReturn("fragment-result");

        handler.include(req, resp, "/fragment");

        verify(returnValueResolverRegistry).resolveReturnValue(any(), eq(pathContext), any(), any());
        assertEquals(HttpStatus.OK, resp.getStatus(), "include 不应修改外层响应状态码");
        assertNull(RequestContextHolder.getRequestAttributes());
    }

    @Test
    void initContextHolders_createsServletWrappersAndRegistersFlushListener() {
        resetThreadLocals();
        when(requestContext.getAttribute(io.springperf.web.support.servlet.ServletAttribute.getAttributeKey()))
                .thenReturn(null);

        boolean result = handler.initContextHolders(req, resp);

        assertTrue(result);
        assertNotNull(RequestContextHolder.getRequestAttributes(), "initContextHolders 应初始化 RequestContextHolder");
        assertInstanceOf(ServletRequestAttributes.class, RequestContextHolder.getRequestAttributes());
        verify(resp).addWriteRespEventListener(any());
    }

    @Test
    void removeContextHolders_resetsRequestContextAndLocale() {
        resetThreadLocals();
        when(requestContext.getAttribute(io.springperf.web.support.servlet.ServletAttribute.getAttributeKey()))
                .thenReturn(null);

        handler.initContextHolders(req, resp);
        assertNotNull(RequestContextHolder.getRequestAttributes());

        handler.removeContextHolders(req, resp);
        assertNull(RequestContextHolder.getRequestAttributes());
        assertNull(LocaleContextHolder.getLocaleContext());
    }

    @Test
    void buildRequestAttributes_withExistingAdapterContext_reusesWrappers() {
        io.springperf.web.support.servlet.context.ServletAdapterContext adapterContext =
                mock(io.springperf.web.support.servlet.context.ServletAdapterContext.class);
        io.springperf.web.support.servlet.PerfHttpServletRequest pReq =
                mock(io.springperf.web.support.servlet.PerfHttpServletRequest.class);
        io.springperf.web.support.servlet.PerfHttpServletResponse pResp =
                mock(io.springperf.web.support.servlet.PerfHttpServletResponse.class);
        when(requestContext.getAttribute(io.springperf.web.support.servlet.ServletAttribute.getAttributeKey()))
                .thenReturn(adapterContext);
        when(adapterContext.getRequest()).thenReturn(pReq);
        when(adapterContext.getResponse()).thenReturn(pResp);

        ServletRequestAttributes attrs = handler.buildRequestAttributes(req, resp);

        assertSame(pReq, attrs.getRequest());
        assertSame(pResp, attrs.getResponse());
    }
}

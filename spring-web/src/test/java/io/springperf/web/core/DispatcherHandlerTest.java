package io.springperf.web.core;

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
import io.springperf.web.core.pool.BizPoolRegistry;
import io.springperf.web.core.retval.ReturnValueResolverRegistry;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DispatcherHandlerTest {

    private DispatcherHandler handler;
    private WebContext webContext;
    private MappingRegistry mappingRegistry;
    private ExceptionRegistry exceptionRegistry;
    private ArgumentResolverRegistry argumentResolverRegistry;
    private ReturnValueResolverRegistry returnValueResolverRegistry;
    private CorsRegistry corsRegistry;
    private InterceptorRegistry interceptorRegistry;
    private BizPoolRegistry bizPoolRegistry;
    private AsyncSupportRegistry asyncSupportRegistry;
    private WebFilterRegistry webFilterRegistry;

    private WebServerHttpRequest createRequest() {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        RequestContext reqCtx = mock(RequestContext.class);
        when(req.getRequestContext()).thenReturn(reqCtx);
        // 模拟 fastAttributes 以支持 RequestAttribute 存取
        Map<RequestAttribute<?>, Object> fastAttrs = new HashMap<>();
        when(reqCtx.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> fastAttrs.get(inv.getArgument(0)));
        doAnswer(inv -> { fastAttrs.put(inv.getArgument(0), inv.getArgument(1)); return null; }).when(reqCtx).setAttribute(any(RequestAttribute.class), any());
        return req;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        handler = new DispatcherHandler();
        webContext = mock(WebContext.class);
        mappingRegistry = mock(MappingRegistry.class);
        exceptionRegistry = mock(ExceptionRegistry.class);
        argumentResolverRegistry = mock(ArgumentResolverRegistry.class);
        returnValueResolverRegistry = mock(ReturnValueResolverRegistry.class);
        corsRegistry = mock(CorsRegistry.class);
        interceptorRegistry = mock(InterceptorRegistry.class);
        bizPoolRegistry = mock(BizPoolRegistry.class);
        asyncSupportRegistry = mock(AsyncSupportRegistry.class);
        webFilterRegistry = mock(WebFilterRegistry.class);

        when(webContext.getWebComponentWithDefault(eq(MappingRegistry.class), any(MappingRegistry.class)))
                .thenReturn(mappingRegistry);
        when(webContext.getWebComponentWithDefault(eq(ExceptionRegistry.class), any(ExceptionRegistry.class)))
                .thenReturn(exceptionRegistry);
        when(webContext.getWebComponentWithDefault(eq(ArgumentResolverRegistry.class), any(ArgumentResolverRegistry.class)))
                .thenReturn(argumentResolverRegistry);
        when(webContext.getWebComponentWithDefault(eq(ReturnValueResolverRegistry.class), any(ReturnValueResolverRegistry.class)))
                .thenReturn(returnValueResolverRegistry);
        when(webContext.getWebComponentWithDefault(eq(CorsRegistry.class), any(CorsRegistry.class)))
                .thenReturn(corsRegistry);
        when(webContext.getWebComponentWithDefault(eq(InterceptorRegistry.class), any(InterceptorRegistry.class)))
                .thenReturn(interceptorRegistry);
        when(webContext.getWebComponentWithDefault(eq(BizPoolRegistry.class), any(BizPoolRegistry.class)))
                .thenReturn(bizPoolRegistry);
        when(webContext.getWebComponentWithDefault(eq(AsyncSupportRegistry.class), any(AsyncSupportRegistry.class)))
                .thenReturn(asyncSupportRegistry);
        when(webContext.getWebComponentWithDefault(eq(WebFilterRegistry.class), any(WebFilterRegistry.class)))
                .thenReturn(webFilterRegistry);

        // doFilter 模拟：新流程中 DefaultFilterChain 调用 handleAfterFilter
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
    }

    // ==================== handle() ====================

    @Test
    void handle_mappingFound_processesRequest() throws Exception {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        PathMappingContext mappingContext = mock(PathMappingContext.class);
        MappingResult matched = MappingResult.matched(mappingContext);
        MappingResult.set(req, matched);
        when(mappingRegistry.mapping(req)).thenReturn(matched);
        when(corsRegistry.corsHandle(any(), any())).thenReturn(false);
        when(interceptorRegistry.preHandle(any(), any())).thenReturn(true);
        when(argumentResolverRegistry.resolveArguments(any(), any(), any())).thenReturn(new Object[0]);

        handler.handle(req, resp);

        verify(interceptorRegistry).preHandle(req, resp);
    }

    @Test
    void handle_mappingNotFound_sends404() {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        MappingResult notFound = MappingResult.notFound();
        MappingResult.set(req, notFound);
        when(mappingRegistry.mapping(req)).thenReturn(notFound);

        handler.handle(req, resp);

        verify(exceptionRegistry).handle(any(ResponseStatusException.class), eq(req), eq(resp));
        verify(interceptorRegistry).afterCompletion(eq(req), eq(resp), any(ResponseStatusException.class));
    }

    @Test
    void handle_methodMismatch_sends405() {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        MappingResult pathMatched = MappingResult.pathMatched(new PathMappingContext[0], true);
        MappingResult.set(req, pathMatched);
        when(mappingRegistry.mapping(req)).thenReturn(pathMatched);

        handler.handle(req, resp);

        verify(exceptionRegistry).handle(any(ResponseStatusException.class), eq(req), eq(resp));
        verify(interceptorRegistry).afterCompletion(eq(req), eq(resp), any(ResponseStatusException.class));
    }

    @Test
    void handle_withExecutor_offloadsToThreadPool() {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        PathMappingContext mappingContext = mock(PathMappingContext.class);
        MappingResult matched = MappingResult.matched(mappingContext);
        MappingResult.set(req, matched);
        when(mappingRegistry.mapping(req)).thenReturn(matched);
        ExecutorService executor = mock(ExecutorService.class);
        when(bizPoolRegistry.determinePool(req, matched)).thenReturn(executor);

        handler.handle(req, resp);

        verify(req).acquire();
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void handle_withoutExecutor_processesSynchronously() throws Exception {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        PathMappingContext mappingContext = mock(PathMappingContext.class);
        MappingResult matched = MappingResult.matched(mappingContext);
        MappingResult.set(req, matched);
        when(mappingRegistry.mapping(req)).thenReturn(matched);
        when(bizPoolRegistry.determinePool(req, matched)).thenReturn(null);
        when(corsRegistry.corsHandle(any(), any())).thenReturn(false);
        when(interceptorRegistry.preHandle(any(), any())).thenReturn(true);
        when(argumentResolverRegistry.resolveArguments(any(), any(), any())).thenReturn(new Object[0]);

        handler.handle(req, resp);

        verify(interceptorRegistry).preHandle(req, resp);
    }

    // ==================== doHandle() ====================

    @Test
    void doHandle_corsHandled_returnsEarly() throws Exception {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        PathMappingContext mappingContext = mock(PathMappingContext.class);
        when(corsRegistry.corsHandle(req, resp)).thenReturn(true);

        handler.doHandle(req, resp, mappingContext);

        // CORS handles the request, then finally still calls invokeWithRealResult
        verify(corsRegistry).corsHandle(req, resp);
        verifyNoInteractions(argumentResolverRegistry);
    }

    @Test
    void doHandle_preHandleReturnsFalse_returnsEarly() throws Exception {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        PathMappingContext mappingContext = mock(PathMappingContext.class);
        when(corsRegistry.corsHandle(req, resp)).thenReturn(false);
        when(interceptorRegistry.preHandle(req, resp)).thenReturn(false);

        handler.doHandle(req, resp, mappingContext);

        verifyNoInteractions(argumentResolverRegistry);
    }

    @Test
    void doHandle_normalFlow_invokesHandlerAndResolvesReturnValue() throws Throwable {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        PathMappingContext mappingContext = mock(PathMappingContext.class);
        when(corsRegistry.corsHandle(req, resp)).thenReturn(false);
        when(interceptorRegistry.preHandle(req, resp)).thenReturn(true);
        Object[] args = new Object[]{};
        when(argumentResolverRegistry.resolveArguments(mappingContext, req, resp)).thenReturn(args);
        Object result = new Object();
        when(mappingContext.invoke(args, req, resp)).thenReturn(result);

        handler.doHandle(req, resp, mappingContext);

        verify(returnValueResolverRegistry).resolveReturnValue(result, mappingContext, req, resp);
    }

    @Test
    void doHandle_exceptionDuringProcessing_caughtByExceptionRegistry() throws Throwable {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        PathMappingContext mappingContext = mock(PathMappingContext.class);
        when(corsRegistry.corsHandle(req, resp)).thenReturn(false);
        when(interceptorRegistry.preHandle(req, resp)).thenReturn(true);
        when(argumentResolverRegistry.resolveArguments(mappingContext, req, resp)).thenThrow(new RuntimeException("test error"));

        handler.doHandle(req, resp, mappingContext);

        verify(exceptionRegistry).handle(any(RuntimeException.class), eq(req), eq(resp));
    }

    // ==================== invokeWithRealResult() ====================

    @Test
    void invokeWithRealResult_callsPostHandleAndAfterCompletion() throws Exception {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        Object result = new Object();
        when(resp.isHandled()).thenReturn(true);

        handler.invokeWithRealResult(req, resp, result, null);

        verify(interceptorRegistry).postHandle(req, resp, result);
        verify(interceptorRegistry).afterCompletion(req, resp, null);
        verify(resp).flush();
    }

    @Test
    void invokeWithRealResult_withException_passesExceptionToAfterCompletion() {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        Throwable exception = new RuntimeException("error");
        when(resp.isHandled()).thenReturn(true);

        handler.invokeWithRealResult(req, resp, null, exception);

        verify(interceptorRegistry).afterCompletion(req, resp, exception);
    }

    @Test
    void invokeWithRealResult_responseNotHandled_noFlush() throws Exception {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.isHandled()).thenReturn(false);

        handler.invokeWithRealResult(req, resp, null, null);

        verify(resp, never()).flush();
    }

    // ==================== asyncDispatch() ====================

    @Test
    void asyncDispatch_concurrentResultIsThrowable_handlesException() {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        Throwable error = new RuntimeException("async error");

        handler.asyncDispatch(req, resp, error);

        verify(exceptionRegistry).handle(error, req, resp);
    }

    @Test
    void asyncDispatch_concurrentResultIsNormal_resolvesReturnValue() throws Exception {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        PathMappingContext mappingContext = mock(PathMappingContext.class);
        MappingResult matched = MappingResult.matched(mappingContext);
        MappingResult.set(req, matched);
        Object concurrentResult = new Object();

        handler.asyncDispatch(req, resp, concurrentResult);

        verify(returnValueResolverRegistry).resolveReturnValue(concurrentResult, mappingContext, req, resp);
    }

    @Test
    void asyncDispatch_alwaysCallsInvokeWithRealResult() {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

        handler.asyncDispatch(req, resp, null);

        verify(interceptorRegistry).postHandle(req, resp, null);
        verify(interceptorRegistry).afterCompletion(req, resp, null);
    }

    @Test
    void asyncDispatch_throwableDuringReturnValueResolution_stillInvokesRealResult() throws Exception {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        PathMappingContext mappingContext = mock(PathMappingContext.class);
        MappingResult matched = MappingResult.matched(mappingContext);
        MappingResult.set(req, matched);
        doThrow(new RuntimeException("resolver error")).when(returnValueResolverRegistry)
                .resolveReturnValue(any(), any(), any(), any());

        Object concurrentResult = new Object();
        handler.asyncDispatch(req, resp, concurrentResult);

        // result is set before resolveReturnValue throws, so it's the concurrentResult
        verify(interceptorRegistry).postHandle(req, resp, concurrentResult);
        verify(interceptorRegistry).afterCompletion(req, resp, null);
    }

    // ==================== initContextHolders / removeContextHolders ====================

    @Test
    void initContextHolders_buildLocaleContextNull_returnsFalse() {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

        boolean result = handler.initContextHolders(req, resp);

        assertFalse(result);
        assertNull(LocaleContextHolder.getLocaleContext());
    }

    @Test
    void removeContextHolders_resetsLocaleContext() {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

        handler.removeContextHolders(req, resp);

        assertNull(LocaleContextHolder.getLocaleContext());
    }

    @Test
    void buildLocaleContext_returnsNull() {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

        assertNull(handler.buildLocaleContext(req, resp));
    }

    // ==================== initWithWebContext ====================

    @Test
    void initWithWebContext_setsWebContext() {
        assertNotNull(handler.getWebContext());
    }

    // ==================== handleException() ====================

    @Test
    void handleException_exceptionRegistryThrows_doesNotPropagate() {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        RuntimeException originalEx = new RuntimeException("original");
        doThrow(new RuntimeException("handler failed")).when(exceptionRegistry).handle(any(), any(), any());

        assertDoesNotThrow(() -> handler.handleException(originalEx, req, resp));
        verify(exceptionRegistry).handle(originalEx, req, resp);
    }

    // ==================== doHandle() - error paths ====================

    @Test
    void doHandle_preHandleThrowsException_caughtByExceptionRegistry() throws Exception {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        PathMappingContext mappingContext = mock(PathMappingContext.class);
        when(corsRegistry.corsHandle(req, resp)).thenReturn(false);
        RuntimeException ex = new RuntimeException("preHandle error");
        when(interceptorRegistry.preHandle(req, resp)).thenThrow(ex);

        handler.doHandle(req, resp, mappingContext);

        verify(exceptionRegistry).handle(ex, req, resp);
    }

    @Test
    void doHandle_resolveReturnValueThrowsException_caughtByExceptionRegistry() throws Throwable {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        PathMappingContext mappingContext = mock(PathMappingContext.class);
        when(corsRegistry.corsHandle(req, resp)).thenReturn(false);
        when(interceptorRegistry.preHandle(req, resp)).thenReturn(true);
        when(argumentResolverRegistry.resolveArguments(mappingContext, req, resp)).thenReturn(new Object[0]);
        Object result = new Object();
        when(mappingContext.invoke(any(), eq(req), eq(resp))).thenReturn(result);
        RuntimeException ex = new RuntimeException("return value resolver error");
        doThrow(ex).when(returnValueResolverRegistry).resolveReturnValue(any(), any(), any(), any());

        handler.doHandle(req, resp, mappingContext);

        verify(exceptionRegistry).handle(ex, req, resp);
    }

    // ==================== invokeWithRealResult() ====================

    @Test
    void invokeWithRealResult_flushFailure_stillCallsInterceptors() throws Exception {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        Object result = new Object();
        when(resp.isHandled()).thenReturn(true);
        doThrow(new IOException("flush failed")).when(resp).flush();

        handler.invokeWithRealResult(req, resp, result, null);

        verify(interceptorRegistry).postHandle(req, resp, result);
        verify(interceptorRegistry).afterCompletion(req, resp, null);
        verify(resp).flush();
    }

    // ==================== handleWithMappingResult() ====================

    @Test
    void handleWithMappingResult_executorRejects_releasesRequest() {
        WebServerHttpRequest req = createRequest();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        PathMappingContext mappingContext = mock(PathMappingContext.class);
        MappingResult matched = MappingResult.matched(mappingContext);
        MappingResult.set(req, matched);
        ExecutorService executor = mock(ExecutorService.class);
        when(bizPoolRegistry.determinePool(req, matched)).thenReturn(executor);
        doThrow(new RejectedExecutionException("pool full")).when(executor).execute(any(Runnable.class));

        assertThrows(RejectedExecutionException.class,
                () -> handler.handleWithMappingResult(req, resp, matched));

        verify(req).acquire();
        verify(req).release();
    }

    @Test
    void handle_multipleRequests_processEachIndependently() throws Exception {
        WebServerHttpRequest req1 = createRequest();
        WebServerHttpRequest req2 = createRequest();
        WebServerHttpResponse resp1 = mock(WebServerHttpResponse.class);
        WebServerHttpResponse resp2 = mock(WebServerHttpResponse.class);
        PathMappingContext ctx1 = mock(PathMappingContext.class);
        PathMappingContext ctx2 = mock(PathMappingContext.class);
        MappingResult matched1 = MappingResult.matched(ctx1);
        MappingResult matched2 = MappingResult.matched(ctx2);
        MappingResult.set(req1, matched1);
        MappingResult.set(req2, matched2);
        when(mappingRegistry.mapping(req1)).thenReturn(matched1);
        when(mappingRegistry.mapping(req2)).thenReturn(matched2);
        when(corsRegistry.corsHandle(any(), any())).thenReturn(false);
        when(interceptorRegistry.preHandle(any(), any())).thenReturn(true);
        when(argumentResolverRegistry.resolveArguments(any(), any(), any())).thenReturn(new Object[0]);

        handler.handle(req1, resp1);
        handler.handle(req2, resp2);

        verify(mappingRegistry).mapping(req1);
        verify(mappingRegistry).mapping(req2);
    }
}
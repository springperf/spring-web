package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.cors.CorsRegistry;
import io.springperf.web.core.exception.ExceptionRegistry;
import io.springperf.web.core.interceptor.InterceptorRegistry;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.retval.ReturnValueResolverRegistry;
import io.springperf.web.filter.WebFilterRegistry;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ManagementDispatcherHandlerTest {

    @Mock WebContext webContext;
    @Mock ManagementMappingRegistry managementMappingRegistry;
    @Mock CorsRegistry corsRegistry;
    @Mock ReturnValueResolverRegistry returnValueResolverRegistry;
    @Mock ExceptionRegistry exceptionRegistry;
    @Mock InterceptorRegistry interceptorRegistry;
    @Mock WebFilterRegistry webFilterRegistry;
    @Mock WebServerHttpRequest request;
    @Mock WebServerHttpResponse response;
    @Mock PathMappingContext mappingContext;
    @Mock MappingResult mappingResult;

    private ManagementDispatcherHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        when(webContext.getWebComponentWithDefault(eq(CorsRegistry.class), any(CorsRegistry.class)))
                .thenReturn(corsRegistry);
        when(webContext.getWebComponentWithDefault(eq(ReturnValueResolverRegistry.class), any(ReturnValueResolverRegistry.class)))
                .thenReturn(returnValueResolverRegistry);
        when(webContext.getWebComponentWithDefault(eq(ExceptionRegistry.class), any(ExceptionRegistry.class)))
                .thenReturn(exceptionRegistry);
        when(webContext.getWebComponentWithDefault(eq(InterceptorRegistry.class), any(InterceptorRegistry.class)))
                .thenReturn(interceptorRegistry);
        when(webContext.getWebComponentWithDefault(eq(WebFilterRegistry.class), any(WebFilterRegistry.class)))
                .thenReturn(webFilterRegistry);

        // doFilter 走完 chain 后调用 terminal(mappingResult)
        try {
            doAnswer(invocation -> {
                WebFilterRegistry.FilterChainTerminal terminal = invocation.getArgument(3);
                WebServerHttpRequest req = invocation.getArgument(0);
                WebServerHttpResponse resp = invocation.getArgument(1);
                MappingResult mappingResult = invocation.getArgument(2);
                terminal.doFilter(req, resp, mappingResult);
                return null;
            }).when(webFilterRegistry).doFilter(any(), any(), any(MappingResult.class), any(WebFilterRegistry.FilterChainTerminal.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        handler = new ManagementDispatcherHandler(webContext, "/actuator", managementMappingRegistry);
        handler.initComponentPhase2();
    }

    @Test
    void constructor_createsHandler() {
        assertNotNull(handler);
    }

    @Test
    void constructor_withTrailingSlash_createsHandler() {
        ManagementDispatcherHandler h = new ManagementDispatcherHandler(
                webContext, "/management/", managementMappingRegistry);
        assertNotNull(h);
    }

    @Test
    void buildOptimizerPipeline_delegatesToManagementMappingRegistry() {
        handler.buildOptimizerPipeline();

        verify(managementMappingRegistry).buildOptimizerPipeline();
    }

    @Test
    void httpHandle_delegatesToHandle() throws Exception {
        when(managementMappingRegistry.mapping(request)).thenReturn(mappingResult);
        when(mappingResult.isMatched()).thenReturn(false);
        when(mappingResult.isMethodMismatch()).thenReturn(false);

        handler.httpHandle(request, response);

        verify(managementMappingRegistry).mapping(request);
    }

    @Test
    void handle_nonMatched_sends404() {
        when(managementMappingRegistry.mapping(request)).thenReturn(mappingResult);
        when(mappingResult.isMatched()).thenReturn(false);
        when(mappingResult.isMethodMismatch()).thenReturn(false);

        handler.handle(request, response);

        verify(response).sendError(HttpStatus.NOT_FOUND, "Not Found");
    }

    @Test
    void handle_nonMatchedMethodMismatch_sends405() {
        when(managementMappingRegistry.mapping(request)).thenReturn(mappingResult);
        when(mappingResult.isMatched()).thenReturn(false);
        when(mappingResult.isMethodMismatch()).thenReturn(true);

        handler.handle(request, response);

        verify(response).sendError(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed");
    }

    @Test
    void handle_matched_delegatesToHandleWithFUllMatch() throws Throwable {
        when(managementMappingRegistry.mapping(request)).thenReturn(mappingResult);
        when(mappingResult.isMatched()).thenReturn(true);
        when(mappingResult.getMatchedContext()).thenReturn(mappingContext);
        when(corsRegistry.corsHandle(request, response)).thenReturn(false);
        when(mappingContext.invoke(null, request, response)).thenReturn("result");

        handler.handle(request, response);

        verify(mappingContext).invoke(null, request, response);
        verify(returnValueResolverRegistry).resolveReturnValue(eq("result"), eq(mappingContext), eq(request), eq(response));
    }

    @Test
    void handleWithFUllMatch_corsPreflight_returnsEarly() throws Throwable {
        when(corsRegistry.corsHandle(request, response)).thenReturn(true);

        handler.handleWithFUllMatch(request, response, MappingResult.matched(mappingContext));

        verify(corsRegistry).corsHandle(request, response);
        verify(mappingContext, never()).invoke(any(), any(), any());
        verify(returnValueResolverRegistry, never()).resolveReturnValue(any(), any(), any(), any());
    }

    @Test
    void handleWithFUllMatch_normalFlow_invokesAndResolves() throws Throwable {
        when(corsRegistry.corsHandle(request, response)).thenReturn(false);
        Object expectedResult = new Object();
        when(mappingContext.invoke(null, request, response)).thenReturn(expectedResult);

        handler.handleWithFUllMatch(request, response, MappingResult.matched(mappingContext));

        verify(mappingContext).invoke(null, request, response);
        verify(returnValueResolverRegistry).resolveReturnValue(expectedResult, mappingContext, request, response);
    }

    @Test
    void handleWithFUllMatch_exception_callsHandleException() throws Throwable {
        when(corsRegistry.corsHandle(request, response)).thenReturn(false);
        RuntimeException ex = new RuntimeException("test error");
        when(mappingContext.invoke(null, request, response)).thenThrow(ex);

        handler.handleWithFUllMatch(request, response, MappingResult.matched(mappingContext));

        verify(exceptionRegistry).handle(eq(ex), eq(request), eq(response));
    }

    @Test
    void handleOnNoMatchMappingContext_methodMismatch_sends405() {
        when(mappingResult.isMatched()).thenReturn(false);
        when(mappingResult.isMethodMismatch()).thenReturn(true);

        handler.handleOnNoMatchMappingContext(request, response, mappingResult);

        verify(response).sendError(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed");
    }

    @Test
    void handleOnNoMatchMappingContext_notFound_sends404() {
        when(mappingResult.isMatched()).thenReturn(false);
        when(mappingResult.isMethodMismatch()).thenReturn(false);

        handler.handleOnNoMatchMappingContext(request, response, mappingResult);

        verify(response).sendError(HttpStatus.NOT_FOUND, "Not Found");
    }
}
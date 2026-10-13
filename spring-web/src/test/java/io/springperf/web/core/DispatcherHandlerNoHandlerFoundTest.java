package io.springperf.web.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.match.HttpMethodMatcher;
import io.springperf.web.core.mapping.match.Matcher;
import io.springperf.web.core.metrics.WebMetrics;
import io.springperf.web.core.pool.BizPoolRegistry;
import io.springperf.web.core.retval.ReturnValueResolverRegistry;
import io.springperf.web.http.WebHttpHeaders;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

/**
 * {@code spring.mvc.throw-exception-if-no-handler-found} 语义：默认 {@code true} 时 404/405 进入异常解析
 * （可被 {@code @ControllerAdvice} 拦截）；设为 {@code false} 时直接 sendError，不进 ExceptionRegistry。
 */
class DispatcherHandlerNoHandlerFoundTest {

    private DispatcherHandler handler;
    private ExceptionRegistry exceptionRegistry;
    private InterceptorRegistry interceptorRegistry;

    private void init(boolean throwExceptionIfNoHandlerFound) throws Exception {
        handler = new DispatcherHandler();
        WebContext webContext = mock(WebContext.class);
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(webContext.getProps()).thenReturn(props);
        when(props.getBoolean(PropertiesConstant.THROW_EXCEPTION_IF_NO_HANDLER_FOUND,
                PropertiesConstant.THROW_EXCEPTION_IF_NO_HANDLER_FOUND_DEFAULT))
                        .thenReturn(throwExceptionIfNoHandlerFound);

        exceptionRegistry = mock(ExceptionRegistry.class);
        interceptorRegistry = mock(InterceptorRegistry.class);
        when(webContext.getWebComponentWithDefault(eq(MappingRegistry.class), any(MappingRegistry.class)))
                .thenReturn(mock(MappingRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(ExceptionRegistry.class), any(ExceptionRegistry.class)))
                .thenReturn(exceptionRegistry);
        when(webContext.getWebComponentWithDefault(eq(ArgumentResolverRegistry.class),
                any(ArgumentResolverRegistry.class))).thenReturn(mock(ArgumentResolverRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(ReturnValueResolverRegistry.class),
                any(ReturnValueResolverRegistry.class))).thenReturn(mock(ReturnValueResolverRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(CorsRegistry.class), any(CorsRegistry.class)))
                .thenReturn(mock(CorsRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(InterceptorRegistry.class), any(InterceptorRegistry.class)))
                .thenReturn(interceptorRegistry);
        when(webContext.getWebComponentWithDefault(eq(BizPoolRegistry.class), any(BizPoolRegistry.class)))
                .thenReturn(mock(BizPoolRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(AsyncSupportRegistry.class), any(AsyncSupportRegistry.class)))
                .thenReturn(mock(AsyncSupportRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(WebFilterRegistry.class), any(WebFilterRegistry.class)))
                .thenReturn(mock(WebFilterRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(WebMetrics.class), any())).thenReturn(mock(WebMetrics.class));

        handler.initWithWebContext(webContext);
    }

    /** 默认（true）：404 进入异常解析，携带 404 状态，并阻塞式走完 afterCompletion。 */
    @Test
    void throwException_default_entersExceptionRegistry() throws Exception {
        init(true);
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

        handler.handleOnNoMatchMappingContext(req, resp, MappingResult.notFound());

        ArgumentCaptor<ResponseStatusException> captured = ArgumentCaptor.forClass(ResponseStatusException.class);
        verify(exceptionRegistry).handle(captured.capture(), eq(req), eq(resp));
        assertEquals(HttpStatus.NOT_FOUND, captured.getValue().getStatus());
        verify(resp, never()).sendError(any(HttpStatus.class), any());
        verify(interceptorRegistry).afterCompletion(eq(req), eq(resp), any());
    }

    /** false：直接 sendError(404)，不进入异常解析（对齐 Boot 默认行为，避免误拦 404）。 */
    @Test
    void throwException_false_sendsErrorDirectly() throws Exception {
        init(false);
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

        handler.handleOnNoMatchMappingContext(req, resp, MappingResult.notFound());

        verify(resp).sendError(HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND.getReasonPhrase());
        verify(exceptionRegistry, never()).handle(any(), any(), any());
        // 无异常对象：afterCompletion 的 ex 为 null
        verify(interceptorRegistry).afterCompletion(eq(req), eq(resp), isNull());
    }

    /** false 且方法不匹配：405（走 sendError 分支，不构造异常），并写出 Allow 头。 */
    @Test
    void throwException_false_methodMismatch_sends405() throws Exception {
        init(false);
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebHttpHeaders headers = new WebHttpHeaders();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getHeaders()).thenReturn(headers);

        handler.handleOnNoMatchMappingContext(req, resp, methodMismatch(HttpMethod.GET, HttpMethod.POST));

        verify(resp).sendError(HttpStatus.METHOD_NOT_ALLOWED, HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase());
        assertEquals(EnumSet.of(HttpMethod.GET, HttpMethod.POST), headers.getAllow(),
                "405 必须带 Allow 头，列出该路径已注册的全部方法");
        verify(exceptionRegistry, never()).handle(any(), any(), any());
    }

    /** 默认（true）+ 方法不匹配：405 异常 + Allow 头（异常路径同样要写语义头）。 */
    @Test
    void methodMismatch_defaultTrue_throws405_withAllowHeader() throws Exception {
        init(true);
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebHttpHeaders headers = new WebHttpHeaders();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getHeaders()).thenReturn(headers);

        handler.handleOnNoMatchMappingContext(req, resp, methodMismatch(HttpMethod.GET));

        ArgumentCaptor<ResponseStatusException> captured = ArgumentCaptor.forClass(ResponseStatusException.class);
        verify(exceptionRegistry).handle(captured.capture(), eq(req), eq(resp));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, captured.getValue().getStatus());
        assertEquals(EnumSet.of(HttpMethod.GET), headers.getAllow());
    }

    /** consumes 不匹配 → 415（对齐 Spring MVC，而非一律 404）。 */
    @Test
    void consumesMismatch_throws415() throws Exception {
        init(true);
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

        handler.handleOnNoMatchMappingContext(req, resp,
                MappingResult.pathMatched(new PathMappingContext[] { contextWithoutMatchers() },
                        MappingResult.MismatchKind.CONSUMES));

        ArgumentCaptor<ResponseStatusException> captured = ArgumentCaptor.forClass(ResponseStatusException.class);
        verify(exceptionRegistry).handle(captured.capture(), eq(req), eq(resp));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, captured.getValue().getStatus());
    }

    /** produces 不匹配 → 406。 */
    @Test
    void producesMismatch_throws406() throws Exception {
        init(true);
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

        handler.handleOnNoMatchMappingContext(req, resp,
                MappingResult.pathMatched(new PathMappingContext[] { contextWithoutMatchers() },
                        MappingResult.MismatchKind.PRODUCES));

        ArgumentCaptor<ResponseStatusException> captured = ArgumentCaptor.forClass(ResponseStatusException.class);
        verify(exceptionRegistry).handle(captured.capture(), eq(req), eq(resp));
        assertEquals(HttpStatus.NOT_ACCEPTABLE, captured.getValue().getStatus());
    }

    /** false + consumes 不匹配：直接 sendError(415)，404 之外的语义同样成立。 */
    @Test
    void throwException_false_consumesMismatch_sends415() throws Exception {
        init(false);
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

        handler.handleOnNoMatchMappingContext(req, resp,
                MappingResult.pathMatched(new PathMappingContext[] { contextWithoutMatchers() },
                        MappingResult.MismatchKind.CONSUMES));

        verify(resp).sendError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, HttpStatus.UNSUPPORTED_MEDIA_TYPE.getReasonPhrase());
        verify(exceptionRegistry, never()).handle(any(), any(), any());
    }

    /** 路径命中但非 method/media 条件不匹配（OTHER）→ 404，且不带 Allow 头。 */
    @Test
    void otherMismatch_throws404_withoutAllowHeader() throws Exception {
        init(true);
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebHttpHeaders headers = new WebHttpHeaders();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getHeaders()).thenReturn(headers);

        handler.handleOnNoMatchMappingContext(req, resp,
                MappingResult.pathMatched(new PathMappingContext[] { contextWithoutMatchers() },
                        MappingResult.MismatchKind.OTHER));

        ArgumentCaptor<ResponseStatusException> captured = ArgumentCaptor.forClass(ResponseStatusException.class);
        verify(exceptionRegistry).handle(captured.capture(), eq(req), eq(resp));
        assertEquals(HttpStatus.NOT_FOUND, captured.getValue().getStatus());
        assertTrue(headers.getAllow() == null || headers.getAllow().isEmpty(), "404 不应带 Allow 头");
    }

    private static PathMappingContext contextWithoutMatchers() {
        PathMappingContext ctx = mock(PathMappingContext.class);
        lenient().when(ctx.getMatchers()).thenReturn(new Matcher[0]);
        return ctx;
    }

    /** 构造「路径命中但方法不匹配」的结果，并让 context 带 HttpMethodMatcher（用于 Allow 收集）。 */
    private static MappingResult methodMismatch(HttpMethod... methods) {
        PathMappingContext ctx = mock(PathMappingContext.class);
        lenient().when(ctx.getMatchers()).thenReturn(new Matcher[] { new HttpMethodMatcher(methods) });
        return MappingResult.pathMatched(new PathMappingContext[] { ctx }, MappingResult.MismatchKind.METHOD);
    }
}

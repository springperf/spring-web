package io.springperf.web.core.exception;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResponseStatusExceptionResolverTest {

    ResponseStatusExceptionResolver resolver;

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    @Mock
    HandlerMethod handler;

    @Mock
    MessageSource messageSource;

    @BeforeEach
    void setUp() {
        resolver = new ResponseStatusExceptionResolver();
    }

    @Test
    void resolveException_null_returnsFalse() {
        assertFalse(resolver.resolveException(request, response, handler, null));
    }

    @Test
    void resolveException_responseStatusException_resolves() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");

        boolean result = resolver.resolveException(request, response, handler, ex);

        assertTrue(result);
        verify(response).sendError(HttpStatus.NOT_FOUND, "not found");
    }

    @Test
    void resolveException_responseStatusException_headersEmpty_noHeaderCopy() {
        // 回归 R3 P1-13：headers 经 ResponseStatusExceptionAdapter 跨版本桥接。
        // 6.1 中 ResponseStatusException 构造器不接收 headers，getHeaders() 恒返回 EMPTY，
        // 复制逻辑必须为空操作——不得因 Adapter 桥接引入 NPE 或误加响应头。
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad");
        HttpHeaders respHeaders = new HttpHeaders();
        // 6.1 下 EMPTY headers 空迭代，getHeaders() 不会真正被访问 → lenient 声明避免 UnnecessaryStubbing
        lenient().when(response.getHeaders()).thenReturn(respHeaders);

        boolean result = resolver.resolveException(request, response, handler, ex);

        assertTrue(result);
        assertTrue(respHeaders.isEmpty(), "headers 为空时不得复制任何响应头");
        verify(response).sendError(HttpStatus.BAD_REQUEST, "bad");
    }

    @Test
    void resolveException_responseStatusException_withHeaders_addsToResponse() {
        // P1-13 反向验证：headers 复制逻辑本身——构造一个确实携带 headers 的 ResponseStatusException
        // 子类（6.1 基类构造器不接收 headers，但 protected 字段/方法可被子类填充），
        // 经 Adapter 桥接后必须复制到响应头。若 Adapter 失效或改回 getResponseHeaders() 直呼，
        // 此测试在 Spring 7 上即编译失败（getResponseHeaders 被移除），反向锁定 P1-13。
        HttpHeaders respHeaders = new HttpHeaders();
        when(response.getHeaders()).thenReturn(respHeaders);

        ResponseStatusException ex = new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad") {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders hs = new HttpHeaders();
                hs.add("X-Custom", "v1");
                return hs;
            }
        };

        boolean result = resolver.resolveException(request, response, handler, ex);

        assertTrue(result);
        assertEquals(java.util.List.of("v1"), respHeaders.get("X-Custom"));
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    static class NotFoundException extends RuntimeException {
    }

    @Test
    void resolveException_responseStatusAnnotation_resolves() {
        NotFoundException ex = new NotFoundException();

        boolean result = resolver.resolveException(request, response, handler, ex);

        assertTrue(result);
        verify(response).sendError(HttpStatus.NOT_FOUND);
    }

    @ResponseStatus(value = HttpStatus.BAD_REQUEST, reason = "bad.request")
    static class BadRequestWithReasonException extends RuntimeException {
    }

    @Test
    void resolveException_responseStatusAnnotation_withReason_withoutMessageSource() {
        BadRequestWithReasonException ex = new BadRequestWithReasonException();

        boolean result = resolver.resolveException(request, response, handler, ex);

        assertTrue(result);
        verify(response).sendError(HttpStatus.BAD_REQUEST, "bad.request");
    }

    @Test
    void resolveException_responseStatusAnnotation_withReason_withMessageSource() {
        resolver.setMessageSource(messageSource);
        when(messageSource.getMessage(eq("bad.request"), isNull(), eq("bad.request"), any()))
                .thenReturn("自定义错误信息");

        BadRequestWithReasonException ex = new BadRequestWithReasonException();

        boolean result = resolver.resolveException(request, response, handler, ex);

        assertTrue(result);
        verify(response).sendError(HttpStatus.BAD_REQUEST, "自定义错误信息");
    }

    @ResponseStatus(HttpStatus.IM_USED)
    static class ImUsedException extends RuntimeException {
    }

    static class WrapperException extends RuntimeException {
        public WrapperException(Throwable cause) {
            super(cause);
        }
    }

    @Test
    void resolveException_annotationNotFound_causeChain() {
        ImUsedException cause = new ImUsedException();
        WrapperException ex = new WrapperException(cause);

        boolean result = resolver.resolveException(request, response, handler, ex);

        assertTrue(result);
        // The resolver recurses to ex.getCause() and finds @ResponseStatus on ImUsedException
        verify(response).sendError(HttpStatus.IM_USED);
    }

    // ----- C2/C3: 参数绑定/消息体解析错误 → 400（对齐 Spring DefaultHandlerExceptionResolver） -----

    @Test
    void resolveException_methodArgumentTypeMismatch_resolvesToBadRequest() {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", Integer.class, "count", null, new RuntimeException("convert"));

        boolean result = resolver.resolveException(request, response, handler, ex);

        assertTrue(result);
        verify(response).sendError(HttpStatus.BAD_REQUEST);
    }

    @Test
    void resolveException_httpMessageNotReadable_resolvesToBadRequest() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("missing body", request);

        boolean result = resolver.resolveException(request, response, handler, ex);

        assertTrue(result);
        verify(response).sendError(HttpStatus.BAD_REQUEST);
    }

    @Test
    void resolveException_causeChain_nullCause_returnsFalse() {
        RuntimeException ex = new RuntimeException("no annotation");

        boolean result = resolver.resolveException(request, response, handler, ex);

        assertFalse(result);
        verify(response, never()).sendError(any(HttpStatus.class));
        verify(response, never()).sendError(any(HttpStatus.class), anyString());
    }

    @Test
    void applyStatusAndReason_withReason_withoutMessageSource() {
        boolean result = resolver.applyStatusAndReason(HttpStatus.BAD_REQUEST, "error reason", response);

        assertTrue(result);
        verify(response).sendError(HttpStatus.BAD_REQUEST, "error reason");
    }

    @Test
    void applyStatusAndReason_withReason_withMessageSource() {
        resolver.setMessageSource(messageSource);
        when(messageSource.getMessage(eq("error.code"), isNull(), eq("error.code"), any()))
                .thenReturn("解析后的错误信息");

        boolean result = resolver.applyStatusAndReason(HttpStatus.BAD_REQUEST, "error.code", response);

        assertTrue(result);
        verify(response).sendError(HttpStatus.BAD_REQUEST, "解析后的错误信息");
    }

    @Test
    void applyStatusAndReason_withoutReason() {
        boolean result = resolver.applyStatusAndReason(HttpStatus.OK, null, response);

        assertTrue(result);
        verify(response).sendError(HttpStatus.OK);
    }
}
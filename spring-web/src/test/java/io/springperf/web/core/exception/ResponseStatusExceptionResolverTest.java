package io.springperf.web.core.exception;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
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
    void resolveException_responseStatusException_withHeaders_resolves() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");

        boolean result = resolver.resolveException(request, response, handler, ex);

        assertTrue(result);
        verify(response).sendError(HttpStatus.NOT_FOUND, "not found");
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
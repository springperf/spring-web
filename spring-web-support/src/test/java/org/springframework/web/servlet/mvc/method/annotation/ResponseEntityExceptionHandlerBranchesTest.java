package org.springframework.web.servlet.mvc.method.annotation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MissingPathVariableException;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@link ResponseEntityExceptionHandler#handleException} 对各类异常的
 * HTTP 状态码映射（Spring MVC 语义对齐）。
 */
class ResponseEntityExceptionHandlerBranchesTest {

    private final ResponseEntityExceptionHandler handler = new ResponseEntityExceptionHandler();

    private WebRequest webRequest() {
        return new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse());
    }

    @Test
    void methodNotSupported_mapsTo405() throws Exception {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("GET", Collections.singletonList("POST"));
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED,
                handler.handleException(ex, webRequest()).getStatusCode());
    }

    @Test
    void mediaTypeNotSupported_mapsTo415() throws Exception {
        HttpMediaTypeNotSupportedException ex =
                new HttpMediaTypeNotSupportedException(new org.springframework.http.MediaType("text", "plain"),
                        Collections.emptyList());
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                handler.handleException(ex, webRequest()).getStatusCode());
    }

    @Test
    void mediaTypeNotAcceptable_mapsTo406() throws Exception {
        HttpMediaTypeNotAcceptableException ex = new HttpMediaTypeNotAcceptableException("no acceptable");
        assertEquals(HttpStatus.NOT_ACCEPTABLE,
                handler.handleException(ex, webRequest()).getStatusCode());
    }

    @Test
    void missingPathVariable_mapsTo500() throws Exception {
        MissingPathVariableException ex =
                new MissingPathVariableException("varName", new org.springframework.core.MethodParameter(getClass().getMethods()[0], 0));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                handler.handleException(ex, webRequest()).getStatusCode());
    }

    @Test
    void typeMismatch_mapsTo400() throws Exception {
        TypeMismatchException ex =
                new MethodArgumentTypeMismatchException("value", Integer.class, "name",
                        new org.springframework.core.MethodParameter(getClass().getMethods()[0], 0), null);
        assertEquals(HttpStatus.BAD_REQUEST,
                handler.handleException(ex, webRequest()).getStatusCode());
    }

    @Test
    void missingServletRequestPart_mapsTo400() throws Exception {
        MissingServletRequestPartException ex = new MissingServletRequestPartException("file");
        assertEquals(HttpStatus.BAD_REQUEST,
                handler.handleException(ex, webRequest()).getStatusCode());
    }

    @Test
    void bindException_mapsTo400() throws Exception {
        BindingResult br = new org.springframework.validation.BeanPropertyBindingResult(new Object(), "target");
        BindException ex = new BindException(br);
        assertEquals(HttpStatus.BAD_REQUEST,
                handler.handleException(ex, webRequest()).getStatusCode());
    }

    @Test
    void noHandlerFound_mapsTo404() throws Exception {
        NoHandlerFoundException ex = new NoHandlerFoundException("GET", "/nope", new org.springframework.http.HttpHeaders());
        assertEquals(HttpStatus.NOT_FOUND,
                handler.handleException(ex, webRequest()).getStatusCode());
    }

    @Test
    void asyncTimeout_mapsTo503() throws Exception {
        AsyncRequestTimeoutException ex = new AsyncRequestTimeoutException();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                handler.handleException(ex, webRequest()).getStatusCode());
    }

    @Test
    void unknownException_returnsInternalServerError_andSetsRequestAttr() throws Exception {
        RuntimeException ex = new RuntimeException("boom");
        WebRequest req = webRequest();
        ResponseEntity<Object> result = handler.handleException(ex, req);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatusCode());
        assertSame(ex, req.getAttribute("jakarta.servlet.error.exception", 0),
                "500 时应设置 error.exception 请求属性");
    }
}
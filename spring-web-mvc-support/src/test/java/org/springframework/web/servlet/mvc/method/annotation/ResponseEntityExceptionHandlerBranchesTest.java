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

    // Spring 5.3 的 MethodParameter 构造会对 parameterIndex 做边界校验，
    // getMethods()[0] 可能返回无参方法（如 wait/equals）导致越界，这里提供确定的有参方法。
    @SuppressWarnings("unused")
    private static void sampleHandler(Object payload) {
    }

    private static java.lang.reflect.Method sampleHandlerMethod() throws NoSuchMethodException {
        return ResponseEntityExceptionHandlerBranchesTest.class.getDeclaredMethod("sampleHandler", Object.class);
    }

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
                new MissingPathVariableException("varName", new org.springframework.core.MethodParameter(sampleHandlerMethod(), 0));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                handler.handleException(ex, webRequest()).getStatusCode());
    }

    @Test
    void typeMismatch_mapsTo400() throws Exception {
        TypeMismatchException ex =
                new MethodArgumentTypeMismatchException("value", Integer.class, "name",
                        new org.springframework.core.MethodParameter(sampleHandlerMethod(), 0), null);
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
        assertSame(ex, req.getAttribute("javax.servlet.error.exception", 0),
                "500 时应设置 error.exception 请求属性");
    }

    @Test
    void missingServletRequestParameter_mapsTo400() throws Exception {
        org.springframework.web.bind.MissingServletRequestParameterException ex =
                new org.springframework.web.bind.MissingServletRequestParameterException("name", "String");
        assertEquals(HttpStatus.BAD_REQUEST,
                handler.handleException(ex, webRequest()).getStatusCode());
    }

    @Test
    void servletRequestBindingException_mapsTo400() throws Exception {
        org.springframework.web.bind.ServletRequestBindingException ex =
                new org.springframework.web.bind.ServletRequestBindingException("binding failed");
        assertEquals(HttpStatus.BAD_REQUEST,
                handler.handleException(ex, webRequest()).getStatusCode());
    }

    @Test
    void conversionNotSupported_mapsTo500() throws Exception {
        org.springframework.beans.ConversionNotSupportedException ex =
                new org.springframework.beans.ConversionNotSupportedException("value", (Class) Integer.class, null);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                handler.handleException(ex, webRequest()).getStatusCode());
    }

    @Test
    void messageNotReadable_mapsTo400() throws Exception {
        org.springframework.http.converter.HttpMessageNotReadableException ex =
                new org.springframework.http.converter.HttpMessageNotReadableException("cannot read");
        assertEquals(HttpStatus.BAD_REQUEST,
                handler.handleException(ex, webRequest()).getStatusCode());
    }

    @Test
    void messageNotWritable_mapsTo500() throws Exception {
        org.springframework.http.converter.HttpMessageNotWritableException ex =
                new org.springframework.http.converter.HttpMessageNotWritableException("cannot write");
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                handler.handleException(ex, webRequest()).getStatusCode());
    }

    @Test
    void methodArgumentNotValid_mapsTo400() throws Exception {
        org.springframework.validation.BeanPropertyBindingResult br =
                new org.springframework.validation.BeanPropertyBindingResult(new Object(), "target");
        org.springframework.web.bind.MethodArgumentNotValidException ex =
                new org.springframework.web.bind.MethodArgumentNotValidException(
                        new org.springframework.core.MethodParameter(sampleHandlerMethod(), 0), br);
        assertEquals(HttpStatus.BAD_REQUEST,
                handler.handleException(ex, webRequest()).getStatusCode());
    }
}

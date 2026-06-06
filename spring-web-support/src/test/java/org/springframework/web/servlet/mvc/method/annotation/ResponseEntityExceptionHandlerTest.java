package org.springframework.web.servlet.mvc.method.annotation;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import static org.junit.jupiter.api.Assertions.*;

class ResponseEntityExceptionHandlerTest {

    private final ResponseEntityExceptionHandler handler = new ResponseEntityExceptionHandler();

    private WebRequest createWebRequest() {
        return new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse());
    }

    @Test
    void handleExceptionInternal_returnsResponseEntity() {
        Exception ex = new RuntimeException("test");
        HttpHeaders headers = new HttpHeaders();
        WebRequest request = createWebRequest();

        ResponseEntity<Object> result = handler.handleExceptionInternal(ex, "body", headers, HttpStatus.BAD_REQUEST, request);

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        assertEquals("body", result.getBody());
    }

    @Test
    void handleExceptionInternal_nullBody_returnsNoBody() {
        Exception ex = new RuntimeException("test");
        WebRequest request = createWebRequest();

        ResponseEntity<Object> result = handler.handleExceptionInternal(ex, null, new HttpHeaders(), HttpStatus.OK, request);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNull(result.getBody());
    }

    @Test
    void handleExceptionInternal_internalError_setsRequestAttribute() {
        Exception ex = new RuntimeException("internal");
        WebRequest request = createWebRequest();

        handler.handleExceptionInternal(ex, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);

        assertSame(ex, request.getAttribute("javax.servlet.error.exception", 0));
    }

    @Test
    void handleException_dispatchesToCorrectMethod() throws Exception {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("param1", "String");
        WebRequest request = createWebRequest();

        ResponseEntity<Object> result = handler.handleException(ex, request);

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
    }

    @Test
    void handleException_genericException_returnsInternalServerError() throws Exception {
        RuntimeException ex = new RuntimeException("unexpected");
        WebRequest request = createWebRequest();

        ResponseEntity<Object> result = handler.handleException(ex, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result.getStatusCode());
    }

    @Test
    void handleException_canOverrideMethod() throws Exception {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("param1", "String");
        WebRequest request = createWebRequest();

        ResponseEntityExceptionHandler customHandler = new ResponseEntityExceptionHandler() {
            @Override
            protected ResponseEntity<Object> handleMissingServletRequestParameter(
                    MissingServletRequestParameterException exc,
                    HttpHeaders headers, HttpStatus status, WebRequest req) {
                return new ResponseEntity<>("custom", HttpStatus.BAD_REQUEST);
            }
        };

        ResponseEntity<Object> result = customHandler.handleException(ex, request);

        assertEquals("custom", result.getBody());
    }
}

package org.springframework.web.servlet;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.*;

class NoHandlerFoundExceptionTest {

    @Test
    void constructor_setsHttpMethod() {
        NoHandlerFoundException ex = new NoHandlerFoundException("GET", "/test", new HttpHeaders());
        assertEquals("GET", ex.getHttpMethod());
    }

    @Test
    void constructor_setsRequestURL() {
        NoHandlerFoundException ex = new NoHandlerFoundException("GET", "/api/users", new HttpHeaders());
        assertEquals("/api/users", ex.getRequestURL());
    }

    @Test
    void constructor_setsHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Accept", "application/json");
        NoHandlerFoundException ex = new NoHandlerFoundException("GET", "/test", headers);
        assertEquals("application/json", ex.getHeaders().getFirst("Accept"));
    }

    @Test
    void constructor_messageContainsHttpMethodAndUrl() {
        NoHandlerFoundException ex = new NoHandlerFoundException("POST", "/api/data", new HttpHeaders());
        assertTrue(ex.getMessage().contains("POST"));
        assertTrue(ex.getMessage().contains("/api/data"));
    }

    @Test
    void extendsServletException() {
        NoHandlerFoundException ex = new NoHandlerFoundException("GET", "/test", new HttpHeaders());
        assertInstanceOf(jakarta.servlet.ServletException.class, ex);
    }
}
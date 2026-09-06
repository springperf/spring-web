package io.springperf.web.support.servlet;

import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ForwardWebServerHttpRequestTest {

    @Mock WebServerHttpRequest originalRequest;

    @Test
    void getPath_returnsForwardPath() {
        when(originalRequest.getURI()).thenReturn(URI.create("http://localhost:8080/original"));
        ForwardWebServerHttpRequest wrapped = new ForwardWebServerHttpRequest(originalRequest, "/target");
        assertEquals("/target", wrapped.getPath());
    }

    @Test
    void getUriStr_returnsForwardPath() {
        when(originalRequest.getURI()).thenReturn(URI.create("http://localhost:8080/original"));
        ForwardWebServerHttpRequest wrapped = new ForwardWebServerHttpRequest(originalRequest, "/target");
        assertEquals("/target", wrapped.getUriStr());
    }

    @Test
    void getUriStrWithQuery_preservesOriginalQuery() {
        when(originalRequest.getURI()).thenReturn(URI.create("http://localhost:8080/original?page=1"));
        ForwardWebServerHttpRequest wrapped = new ForwardWebServerHttpRequest(originalRequest, "/target");
        assertEquals("/target?page=1", wrapped.getUriStrWithQuery());
    }

    @Test
    void getUriStrWithQuery_replacesWithNewQuery() {
        when(originalRequest.getURI()).thenReturn(URI.create("http://localhost:8080/original?old=1"));
        ForwardWebServerHttpRequest wrapped = new ForwardWebServerHttpRequest(originalRequest, "/target?new=1");
        assertEquals("/target?new=1", wrapped.getUriStrWithQuery());
    }

    @Test
    void getUriStrWithQuery_noOriginalQuery() {
        when(originalRequest.getURI()).thenReturn(URI.create("http://localhost:8080/original"));
        ForwardWebServerHttpRequest wrapped = new ForwardWebServerHttpRequest(originalRequest, "/target");
        assertEquals("/target", wrapped.getUriStrWithQuery());
    }

    @Test
    void getUriStr_doesNotContainQuery() {
        when(originalRequest.getURI()).thenReturn(URI.create("http://localhost:8080/original?page=1"));
        ForwardWebServerHttpRequest wrapped = new ForwardWebServerHttpRequest(originalRequest, "/target");
        assertEquals("/target", wrapped.getUriStr());
    }

    @Test
    void getURI_containsFullUrl() {
        when(originalRequest.getURI()).thenReturn(URI.create("http://localhost:8080/original"));
        ForwardWebServerHttpRequest wrapped = new ForwardWebServerHttpRequest(originalRequest, "/target");
        assertEquals("http://localhost:8080/target", wrapped.getURI().toString());
    }

    @Test
    void getURI_withQuery() {
        when(originalRequest.getURI()).thenReturn(URI.create("http://localhost:8080/original?page=1"));
        ForwardWebServerHttpRequest wrapped = new ForwardWebServerHttpRequest(originalRequest, "/target");
        assertEquals("http://localhost:8080/target?page=1", wrapped.getURI().toString());
    }

    @Test
    void delegatesToOriginal() {
        when(originalRequest.getURI()).thenReturn(URI.create("http://localhost:8080/original"));
        when(originalRequest.getMethodValue()).thenReturn("GET");
        ForwardWebServerHttpRequest wrapped = new ForwardWebServerHttpRequest(originalRequest, "/target");
        assertEquals("GET", wrapped.getMethodValue());
    }
}
package io.springperf.web.core.cors;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerfCorsProcessorTest {

    PerfCorsProcessor processor;

    @Mock WebServerHttpRequest request;
    @Mock WebServerHttpResponse response;
    @Mock HttpHeaders requestHeaders;

    HttpHeaders responseHeaders;

    @BeforeEach
    void setUp() {
        processor = new PerfCorsProcessor();
        responseHeaders = new HttpHeaders();
        when(response.getHeaders()).thenReturn(responseHeaders);
        when(request.getHeaders()).thenReturn(requestHeaders);
    }

    @Test void process_nonCorsRequest_addsVaryHeadersAndReturnsTrue() throws Exception {
        when(requestHeaders.getOrigin()).thenReturn(null);
        boolean result = processor.process(null, request, response);
        assertTrue(result);
        List<String> vary = responseHeaders.get(HttpHeaders.VARY);
        assertNotNull(vary);
        assertTrue(vary.contains(HttpHeaders.ORIGIN));
        assertTrue(vary.contains(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD));
        assertTrue(vary.contains(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS));
    }

    @Test void process_nonCorsRequest_varyHeadersAlreadyPresent_appendsMissing() throws Exception {
        when(requestHeaders.getOrigin()).thenReturn(null);
        responseHeaders.add(HttpHeaders.VARY, "Accept");
        boolean result = processor.process(null, request, response);
        assertTrue(result);
        List<String> vary = responseHeaders.get(HttpHeaders.VARY);
        assertEquals(4, vary.size());
        assertTrue(vary.contains("Accept"));
        assertTrue(vary.contains(HttpHeaders.ORIGIN));
        assertTrue(vary.contains(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD));
        assertTrue(vary.contains(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS));
    }

    @Test void process_corsRequest_hasAccessControlAllowOrigin_returnsTrue() throws Exception {
        when(requestHeaders.getOrigin()).thenReturn("http://other-origin.com");
        when(request.getURI()).thenReturn(URI.create("http://localhost/path"));
        responseHeaders.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://existing-origin.com");
        boolean result = processor.process(null, request, response);
        assertTrue(result);
    }

    @Test void process_preFlightRequest_nullConfig_rejects() throws Exception {
        when(requestHeaders.getOrigin()).thenReturn("http://other-origin.com");
        when(request.getURI()).thenReturn(URI.create("http://localhost/path"));
        when(request.getMethod()).thenReturn(HttpMethod.OPTIONS);
        when(requestHeaders.containsKey(HttpHeaders.ORIGIN)).thenReturn(true);
        when(requestHeaders.containsKey(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD)).thenReturn(true);
        when(response.getBody()).thenReturn(new ByteArrayOutputStream());
        boolean result = processor.process(null, request, response);
        assertFalse(result);
        verify(response).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test void process_actualCorsRequest_nullConfig_returnsTrue() throws Exception {
        when(requestHeaders.getOrigin()).thenReturn("http://other-origin.com");
        when(request.getURI()).thenReturn(URI.create("http://localhost/path"));
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        boolean result = processor.process(null, request, response);
        assertTrue(result);
    }

    @Test void process_withValidConfig_returnsTrueAndSetsHeaders() throws Exception {
        when(requestHeaders.getOrigin()).thenReturn("http://other-origin.com");
        when(request.getURI()).thenReturn(URI.create("http://localhost/path"));
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        CorsConfiguration config = new CorsConfiguration();
        config.applyPermitDefaultValues();
        boolean result = processor.process(config, request, response);
        assertTrue(result);
        assertNotNull(responseHeaders.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
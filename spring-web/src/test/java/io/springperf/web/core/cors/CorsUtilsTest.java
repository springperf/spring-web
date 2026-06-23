package io.springperf.web.core.cors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.ServerHttpRequest;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CorsUtilsTest {

    @Mock
    ServerHttpRequest request;

    @Mock
    HttpHeaders headers;

    @Test
    void isCorsRequest_nullOrigin_returnsFalse() {
        when(request.getHeaders()).thenReturn(headers);
        when(headers.getOrigin()).thenReturn(null);

        assertFalse(CorsUtils.isCorsRequest(request));
    }

    @Test
    void isCorsRequest_sameOriginExact_returnsFalse() {
        when(request.getHeaders()).thenReturn(headers);
        when(headers.getOrigin()).thenReturn("http://localhost:8080");
        when(request.getURI()).thenReturn(URI.create("http://localhost:8080/path"));

        assertFalse(CorsUtils.isCorsRequest(request));
    }

    @Test
    void isCorsRequest_sameOriginDefaultPort_returnsFalse() {
        when(request.getHeaders()).thenReturn(headers);
        when(headers.getOrigin()).thenReturn("http://localhost");
        when(request.getURI()).thenReturn(URI.create("http://localhost/path"));

        assertFalse(CorsUtils.isCorsRequest(request));
    }

    @Test
    void isCorsRequest_differentOrigin_returnsTrue() {
        when(request.getHeaders()).thenReturn(headers);
        when(headers.getOrigin()).thenReturn("http://other-origin.com");
        when(request.getURI()).thenReturn(URI.create("http://localhost:8080/path"));

        assertTrue(CorsUtils.isCorsRequest(request));
    }

    @Test
    void isCorsRequest_differentPort_returnsTrue() {
        when(request.getHeaders()).thenReturn(headers);
        when(headers.getOrigin()).thenReturn("http://localhost:9090");
        when(request.getURI()).thenReturn(URI.create("http://localhost:8080/path"));

        assertTrue(CorsUtils.isCorsRequest(request));
    }

    @Test
    void isCorsRequest_differentScheme_returnsTrue() {
        when(request.getHeaders()).thenReturn(headers);
        when(headers.getOrigin()).thenReturn("https://localhost:8080");
        when(request.getURI()).thenReturn(URI.create("http://localhost:8080/path"));

        assertTrue(CorsUtils.isCorsRequest(request));
    }

    @Test
    void isCorsRequest_differentHost_returnsTrue() {
        when(request.getHeaders()).thenReturn(headers);
        when(headers.getOrigin()).thenReturn("http://example.com:8080");
        when(request.getURI()).thenReturn(URI.create("http://localhost:8080/path"));

        assertTrue(CorsUtils.isCorsRequest(request));
    }

    @Test
    void isPreFlightRequest_allConditionsMet_returnsTrue() {
        when(request.getHeaders()).thenReturn(headers);
        when(request.getMethod()).thenReturn(HttpMethod.OPTIONS);
        when(headers.containsKey(HttpHeaders.ORIGIN)).thenReturn(true);
        when(headers.containsKey(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD)).thenReturn(true);

        assertTrue(CorsUtils.isPreFlightRequest(request));
    }

    @Test
    void isPreFlightRequest_notOptions_returnsFalse() {
        when(request.getMethod()).thenReturn(HttpMethod.GET);

        assertFalse(CorsUtils.isPreFlightRequest(request));
    }

    @Test
    void isPreFlightRequest_missingOrigin_returnsFalse() {
        when(request.getHeaders()).thenReturn(headers);
        when(request.getMethod()).thenReturn(HttpMethod.OPTIONS);
        when(headers.containsKey(HttpHeaders.ORIGIN)).thenReturn(false);

        assertFalse(CorsUtils.isPreFlightRequest(request));
    }

    @Test
    void isPreFlightRequest_missingAccessControlRequestMethod_returnsFalse() {
        when(request.getHeaders()).thenReturn(headers);
        when(request.getMethod()).thenReturn(HttpMethod.OPTIONS);
        when(headers.containsKey(HttpHeaders.ORIGIN)).thenReturn(true);
        when(headers.containsKey(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD)).thenReturn(false);

        assertFalse(CorsUtils.isPreFlightRequest(request));
    }
}

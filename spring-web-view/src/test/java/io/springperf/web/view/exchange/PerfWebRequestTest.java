package io.springperf.web.view.exchange;

import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PerfWebRequestTest {

    private PerfWebRequest request(String uri) {
        WebServerHttpRequest nativeReq = mock(WebServerHttpRequest.class);
        when(nativeReq.getURI()).thenReturn(URI.create(uri));
        return new PerfWebRequest(nativeReq, "");
    }

    private PerfWebRequest requestWithoutUri() {
        return new PerfWebRequest(mock(WebServerHttpRequest.class), "");
    }

    @Test
    void getServerPort_explicitPort_isPreserved() {
        assertEquals(Integer.valueOf(8443), request("https://example.com:8443/page").getServerPort());
    }

    @Test
    void getServerPort_httpsWithoutPort_returns443() {
        assertEquals(Integer.valueOf(443), request("https://example.com/page").getServerPort());
    }

    @Test
    void getServerPort_httpWithoutPort_returns80() {
        assertEquals(Integer.valueOf(80), request("http://example.com/page").getServerPort());
    }

    @Test
    void getServerName_usesUriHost() {
        assertEquals("example.com", request("https://example.com:8443/page").getServerName());
    }

    @Test
    void getScheme_usesUriScheme() {
        assertEquals("https", request("https://example.com/page").getScheme());
    }

    @Test
    void getSchemeAndServerName_nullUri_useDefaults() {
        assertEquals("http", requestWithoutUri().getScheme());
        assertEquals("localhost", requestWithoutUri().getServerName());
        assertEquals(Integer.valueOf(80), requestWithoutUri().getServerPort());
    }

    @Test
    void getQueryString_withoutQuery_returnsNull() {
        assertNull(request("http://example.com/page").getQueryString());
        assertEquals("x=1", request("http://example.com/page?x=1").getQueryString());
    }

    @Test
    void getHeaderValues_missingHeader_returnsEmptyArray() {
        WebServerHttpRequest nativeReq = mock(WebServerHttpRequest.class);
        when(nativeReq.getHeaders()).thenReturn(new HttpHeaders());
        PerfWebRequest request = new PerfWebRequest(nativeReq, "");
        assertFalse(request.containsHeader("missing"));
        assertEquals(0, request.getHeaderCount());
        assertArrayEquals(new String[0], request.getHeaderValues("missing"));
    }

    @Test
    void getParameterValues_missingParameter_returnsEmptyArray() {
        WebServerHttpRequest nativeReq = mock(WebServerHttpRequest.class);
        when(nativeReq.getParameterMap()).thenReturn(new LinkedMultiValueMap<>());
        PerfWebRequest request = new PerfWebRequest(nativeReq, "");
        assertFalse(request.containsParameter("missing"));
        assertArrayEquals(new String[0], request.getParameterValues("missing"));
    }

    @Test
    void cookieMethods_areEmptyByDefault() {
        PerfWebRequest request = requestWithoutUri();
        assertFalse(request.containsCookie("any"));
        assertEquals(0, request.getCookieCount());
        assertTrue(request.getAllCookieNames().isEmpty());
        assertTrue(request.getCookieMap().isEmpty());
        assertArrayEquals(new String[0], request.getCookieValues("any"));
    }
}

package io.springperf.web.view.exchange;

import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PerfWebRequestTest {

    private PerfWebRequest request(String uri) {
        WebServerHttpRequest nativeReq = mock(WebServerHttpRequest.class);
        when(nativeReq.getURI()).thenReturn(URI.create(uri));
        return new PerfWebRequest(nativeReq, "");
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
}

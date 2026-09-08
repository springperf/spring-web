package io.springperf.web.core.filter;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccessLogWebFilterTest {

    @Mock WebServerHttpRequest request;
    @Mock WebServerHttpResponse response;
    @Mock FilterChain chain;

    @BeforeEach
    void setUp() {
        lenient().when(request.getRemoteAddress()).thenReturn(new InetSocketAddress("192.168.1.10", 1234));
        lenient().when(request.getMethodValue()).thenReturn("GET");
        lenient().when(request.getUriStrWithQuery()).thenReturn("/api/users?id=1");
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "TestAgent/1.0");
        lenient().when(request.getHeaders()).thenReturn(headers);
        lenient().when(response.getStatus()).thenReturn(HttpStatus.OK);
    }

    @Test
    void defaultConstructor_usesDefaultFormat() throws Exception {
        AccessLogWebFilter filter = new AccessLogWebFilter();
        assertEquals(Integer.MIN_VALUE, filter.getOrder(), "Order 应为 MIN_VALUE 以最早执行");
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    void nullFormat_fallsBackToDefault() throws Exception {
        AccessLogWebFilter filter = new AccessLogWebFilter(null);
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    void customFormat_rendersAllTokens() throws Exception {
        AccessLogWebFilter filter = new AccessLogWebFilter("%h %m %U %Tms %s \"%u\"");
        filter.doFilter(request, response, chain);
        verify(request).getMethodValue();
        verify(request).getUriStrWithQuery();
        verify(request, atLeastOnce()).getHeaders();
        verify(response, atLeastOnce()).getStatus();
    }

    @Test
    void doFilter_invokesChain_insideTryFinally() throws Exception {
        AccessLogWebFilter filter = new AccessLogWebFilter("%m");
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verify(response, atLeastOnce()).getStatus();
    }

    @Test
    void chainThrows_stillLogsInFinally() throws Exception {
        doThrow(new RuntimeException("boom")).when(chain).doFilter(request, response);
        AccessLogWebFilter filter = new AccessLogWebFilter("%s");
        assertThrows(RuntimeException.class, () -> filter.doFilter(request, response, chain));
        verify(response, atLeastOnce()).getStatus();
    }

    @Test
    void nullStatus_logsZero() throws Exception {
        lenient().when(response.getStatus()).thenReturn(null);
        AccessLogWebFilter filter = new AccessLogWebFilter("%s");
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    void nullRemoteAddress_logsDash() throws Exception {
        when(request.getRemoteAddress()).thenReturn(null);
        AccessLogWebFilter filter = new AccessLogWebFilter("%h");
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    void nullUserAgent_logsDash() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        when(request.getHeaders()).thenReturn(headers);
        AccessLogWebFilter filter = new AccessLogWebFilter("%u");
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    void formatWithUnknownToken_keepsLiteral() throws Exception {
        AccessLogWebFilter filter = new AccessLogWebFilter("literal%x");
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    void formatWithConsecutivePercent_onlyKnownTokenParsed() throws Exception {
        AccessLogWebFilter filter = new AccessLogWebFilter("%%m");
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }
}

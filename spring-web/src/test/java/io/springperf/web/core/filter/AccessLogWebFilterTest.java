package io.springperf.web.core.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

@ExtendWith(MockitoExtension.class)
class AccessLogWebFilterTest {

    @Mock
    WebServerHttpRequest request;
    @Mock
    WebServerHttpResponse response;
    @Mock
    FilterChain chain;
    @Mock
    AccessLogWriter writer;

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

    private AccessLogWebFilter filter(String format) {
        return new AccessLogWebFilter(format, writer);
    }

    private String loggedLine() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(writer).write(captor.capture());
        return captor.getValue();
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
        filter(null).doFilter(request, response, chain);

        String line = loggedLine();
        assertTrue(line.startsWith("192.168.1.10 GET /api/users?id=1 "), line);
        assertTrue(line.contains("ms 200 \"TestAgent/1.0\""), line);
    }

    @Test
    void customFormat_rendersAllTokens() throws Exception {
        filter("%h %m %U %Tms %s \"%u\"").doFilter(request, response, chain);

        String line = loggedLine();
        assertTrue(line.matches("192\\.168\\.1\\.10 GET /api/users\\?id=1 \\d+ms 200 \"TestAgent/1\\.0\""),
                "访问日志各占位符必须全部渲染，实际: " + line);
    }

    @Test
    void doFilter_invokesChain_insideTryFinally() throws Exception {
        filter("%m").doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals("GET", loggedLine());
    }

    @Test
    void chainThrows_stillLogsInFinally() throws Exception {
        doThrow(new RuntimeException("boom")).when(chain).doFilter(request, response);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> filter("%s").doFilter(request, response, chain));
        assertEquals("boom", ex.getMessage(), "chain 的异常必须原样传播");
        assertEquals("200", loggedLine(), "chain 抛异常时 finally 仍必须写出状态码");
    }

    @Test
    void nullStatus_logsZero() throws Exception {
        lenient().when(response.getStatus()).thenReturn(null);
        filter("%s").doFilter(request, response, chain);

        assertEquals("0", loggedLine());
    }

    @Test
    void nullRemoteAddress_logsDash() throws Exception {
        when(request.getRemoteAddress()).thenReturn(null);
        filter("%h").doFilter(request, response, chain);

        assertEquals("-", loggedLine());
    }

    @Test
    void nullUserAgent_logsDash() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        when(request.getHeaders()).thenReturn(headers);
        filter("%u").doFilter(request, response, chain);

        assertEquals("-", loggedLine());
    }

    @Test
    void formatWithUnknownToken_keepsLiteral() throws Exception {
        filter("literal%x").doFilter(request, response, chain);

        assertEquals("literal%x", loggedLine());
    }

    @Test
    void formatWithConsecutivePercent_onlyKnownTokenParsed() throws Exception {
        filter("%%m").doFilter(request, response, chain);

        assertEquals("%GET", loggedLine());
    }
}

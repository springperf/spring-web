package io.springperf.web.core.arg.resolver;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.codec.HttpBodyCodecRegistry;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.RequestEntity;

import java.lang.reflect.Method;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HttpEntityArgResolverTest {

    @Mock
    WebContext webContext;

    @Mock
    HttpBodyCodecRegistry httpBodyCodecRegistry;

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    private void stubCodecRegistry() {
        when(webContext.getWebComponent(HttpBodyCodecRegistry.class)).thenReturn(httpBodyCodecRegistry);
    }

    @Test
    void resolveArgument_httpEntity_returnsHttpEntity() throws Exception {
        stubCodecRegistry();
        Method method = getClass().getMethod("httpEntityParam", HttpEntity.class);
        MethodParameter mp = new MethodParameter(method, 0);

        when(httpBodyCodecRegistry.readBody(any(), eq(mp), eq(request), eq(request)))
                .thenReturn("body-content");
        when(request.getHeaders()).thenReturn(new HttpHeaders());

        HttpEntityArgResolver resolver = new HttpEntityArgResolver(webContext, mp);
        Object result = resolver.resolveArgument(request, response);

        assertInstanceOf(HttpEntity.class, result);
        assertEquals("body-content", ((HttpEntity<?>) result).getBody());
    }

    @Test
    void resolveArgument_requestEntity_returnsRequestEntity() throws Exception {
        stubCodecRegistry();
        Method method = getClass().getMethod("requestEntityParam", RequestEntity.class);
        MethodParameter mp = new MethodParameter(method, 0);

        when(httpBodyCodecRegistry.readBody(any(), eq(mp), eq(request), eq(request)))
                .thenReturn("body-content");
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        when(request.getMethod()).thenReturn(org.springframework.http.HttpMethod.POST);
        when(request.getURI()).thenReturn(URI.create("/test"));

        HttpEntityArgResolver resolver = new HttpEntityArgResolver(webContext, mp);
        Object result = resolver.resolveArgument(request, response);

        assertInstanceOf(RequestEntity.class, result);
        assertEquals("body-content", ((RequestEntity<?>) result).getBody());
        assertEquals(org.springframework.http.HttpMethod.POST, ((RequestEntity<?>) result).getMethod());
    }

    @SuppressWarnings("unused")
    public void httpEntityParam(HttpEntity<String> entity) {}

    @SuppressWarnings("unused")
    public void requestEntityParam(RequestEntity<String> entity) {}
}
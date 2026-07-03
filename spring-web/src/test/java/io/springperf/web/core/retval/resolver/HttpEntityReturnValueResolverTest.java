package io.springperf.web.core.retval.resolver;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.codec.HttpBodyCodecRegistry;
import io.springperf.web.http.WebHttpHeaders;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HttpEntityReturnValueResolverTest {

    HttpEntityReturnValueResolver resolver = new HttpEntityReturnValueResolver();

    @Mock
    HttpBodyCodecRegistry codecRegistry;

    @Mock
    WebContext webContext;

    @Mock
    WebServerHttpResponse response;

    @Mock
    WebServerHttpRequest request;

    @BeforeEach
    void setUp() throws Exception {
        when(webContext.getWebComponent(HttpBodyCodecRegistry.class)).thenReturn(codecRegistry);
        resolver.initWithWebContext(webContext);
    }

    @Test
    void supportsReturnType_httpEntity_returnsTrue() throws Exception {
        assertTrue(resolver.supportsReturnType(returnParam("entityReturn", HttpEntity.class), null));
    }

    @Test
    void supportsReturnType_responseEntity_returnsTrue() throws Exception {
        assertTrue(resolver.supportsReturnType(returnParam("responseEntityReturn", ResponseEntity.class), null));
    }

    @Test
    void supportsReturnType_string_returnsFalse() throws Exception {
        assertFalse(resolver.supportsReturnType(returnParam("stringReturn", String.class), null));
    }

    @Test
    void supportsReturnValue_httpEntity_returnsTrue() {
        assertTrue(resolver.supportsReturnValue(new HttpEntity<>("body"), null, null));
    }

    @Test
    void supportsReturnValue_string_returnsFalse() {
        assertFalse(resolver.supportsReturnValue("string", null, null));
    }

    @Test
    void resolveReturnValue_responseEntity_setsStatusAndHeadersAndBody() throws Exception {
        HttpHeaders entityHeaders = new HttpHeaders();
        entityHeaders.put("X-Custom", Collections.singletonList("value"));
        ResponseEntity<String> entity = new ResponseEntity<>("body", entityHeaders, HttpStatus.ACCEPTED);
        HttpHeaders respHeaders = new HttpHeaders();
        doReturn(respHeaders).when(response).getHeaders();

        resolver.resolveReturnValue(entity, null, request, response);

        verify(response).setStatusCode(HttpStatus.ACCEPTED);
        assertEquals("value", respHeaders.getFirst("X-Custom"));
        verify(codecRegistry).writeBody("body", null, request, response);
    }

    @Test
    void resolveReturnValue_httpEntity_noStatus_doesNotSetStatusCode() throws Exception {
        HttpEntity<String> entity = new HttpEntity<>("body", new WebHttpHeaders());
        HttpHeaders respHeaders = new HttpHeaders();
        doReturn(respHeaders).when(response).getHeaders();

        resolver.resolveReturnValue(entity, null, request, response);

        verify(response, never()).setStatusCode(any());
        verify(codecRegistry).writeBody("body", null, request, response);
    }

    private MethodParameter returnParam(String methodName, Class<?> resultType) throws Exception {
        Method method = getClass().getMethod(methodName);
        return new MethodParameter(method, -1);
    }

    @SuppressWarnings("unused")
    public HttpEntity<?> entityReturn() { return null; }
    @SuppressWarnings("unused")
    public ResponseEntity<?> responseEntityReturn() { return null; }
    @SuppressWarnings("unused")
    public String stringReturn() { return null; }
}
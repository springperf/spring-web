package io.springperf.web.core.retval.resolver;

import io.springperf.web.core.codec.HttpBodyCodecRegistry;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.ResponseBody;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JsonBodyReturnValueResolverTest {

    JsonBodyReturnValueResolver resolver = new JsonBodyReturnValueResolver();

    @Mock
    HttpBodyCodecRegistry codecRegistry;

    @BeforeEach
    void setUp() throws Exception {
        Field f = JsonBodyReturnValueResolver.class.getDeclaredField("httpBodyCodecRegistry");
        f.setAccessible(true);
        f.set(resolver, codecRegistry);
    }

    @Test
    void supportsReturnType_classAnnotated_returnsTrue() throws Exception {
        Method method = ResponseBodyOnClassController.class.getMethod("hello");
        MethodParameter mp = new MethodParameter(method, -1);
        assertTrue(resolver.supportsReturnType(mp, null));
    }

    @Test
    void supportsReturnType_methodAnnotated_returnsTrue() throws Exception {
        Method method = MethodAnnotatedController.class.getMethod("hello");
        MethodParameter mp = new MethodParameter(method, -1);
        assertTrue(resolver.supportsReturnType(mp, null));
    }

    @Test
    void supportsReturnType_noAnnotation_returnsFalse() throws Exception {
        Method method = PlainController.class.getMethod("hello");
        MethodParameter mp = new MethodParameter(method, -1);
        assertFalse(resolver.supportsReturnType(mp, null));
    }

    @Test
    void supportsReturnValue_alwaysReturnsTrue() {
        assertTrue(resolver.supportsReturnValue("anything", null, null));
        assertTrue(resolver.supportsReturnValue(null, null, null));
        assertTrue(resolver.supportsReturnValue(123, null, null));
    }

    @Test
    void resolveReturnValue_delegatesToCodecRegistry() throws Exception {
        Object returnValue = "test-body";
        MethodParameter returnType = mock(MethodParameter.class);
        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        resolver.resolveReturnValue(returnValue, returnType, request, response);

        verify(codecRegistry).writeBody(returnValue, returnType, request, response);
    }

    @Test
    void getOrder_returnsMaxMinus100() {
        assertEquals(Integer.MAX_VALUE - 100, resolver.getOrder());
    }

    @ResponseBody
    static class ResponseBodyOnClassController {
        public String hello() { return "hello"; }
    }

    static class MethodAnnotatedController {
        @ResponseBody
        public String hello() { return "hello"; }
    }

    static class PlainController {
        public String hello() { return "hello"; }
    }
}

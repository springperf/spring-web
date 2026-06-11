package io.springperf.web.core.retval.resolver;

import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ByteArrayReturnValueResolverTest {

    ByteArrayReturnValueResolver resolver = new ByteArrayReturnValueResolver();

    @Mock
    WebServerHttpResponse response;

    @Test
    void supportsReturnType_byteArray_returnsTrue() throws Exception {
        assertTrue(resolver.supportsReturnType(returnParam("byteArrayReturn", byte[].class), null));
    }

    @Test
    void supportsReturnType_string_returnsFalse() throws Exception {
        assertFalse(resolver.supportsReturnType(returnParam("stringReturn", String.class), null));
    }

    @Test
    void supportsReturnValue_byteArray_returnsTrue() {
        assertTrue(resolver.supportsReturnValue(new byte[0], null, null));
    }

    @Test
    void supportsReturnValue_string_returnsFalse() {
        assertFalse(resolver.supportsReturnValue("string", null, null));
    }

    @Test
    void resolveReturnValue_callsWriteBytes() throws Exception {
        byte[] data = "hello".getBytes();
        resolver.resolveReturnValue(data, null, null, response);
        verify(response).writeBytes(data);
    }

    private MethodParameter returnParam(String methodName, Class<?> returnType) throws Exception {
        Method method = getClass().getMethod(methodName);
        return new MethodParameter(method, -1);
    }

    @SuppressWarnings("unused")
    public byte[] byteArrayReturn() { return null; }
    @SuppressWarnings("unused")
    public String stringReturn() { return null; }
}
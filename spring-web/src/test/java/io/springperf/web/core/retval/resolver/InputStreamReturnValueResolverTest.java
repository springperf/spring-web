package io.springperf.web.core.retval.resolver;

import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class InputStreamReturnValueResolverTest {

    InputStreamReturnValueResolver resolver = new InputStreamReturnValueResolver();

    @Mock
    WebServerHttpResponse response;

    @Test
    void supportsReturnType_inputStream_returnsTrue() throws Exception {
        assertTrue(resolver.supportsReturnType(returnParam("streamReturn", InputStream.class), null));
    }

    @Test
    void supportsReturnType_string_returnsFalse() throws Exception {
        assertFalse(resolver.supportsReturnType(returnParam("stringReturn", String.class), null));
    }

    @Test
    void supportsReturnValue_inputStream_returnsTrue() {
        assertTrue(resolver.supportsReturnValue(new ByteArrayInputStream(new byte[0]), null, null));
    }

    @Test
    void supportsReturnValue_string_returnsFalse() {
        assertFalse(resolver.supportsReturnValue("string", null, null));
    }

    @Test
    void resolveReturnValue_callsWriteStream() throws Exception {
        ByteArrayInputStream stream = new ByteArrayInputStream("data".getBytes());
        resolver.resolveReturnValue(stream, null, null, response);
        verify(response).writeStream(stream);
    }

    private MethodParameter returnParam(String methodName, Class<?> returnType) throws Exception {
        Method method = getClass().getMethod(methodName);
        return new MethodParameter(method, -1);
    }

    @SuppressWarnings("unused")
    public InputStream streamReturn() { return null; }
    @SuppressWarnings("unused")
    public String stringReturn() { return null; }
}
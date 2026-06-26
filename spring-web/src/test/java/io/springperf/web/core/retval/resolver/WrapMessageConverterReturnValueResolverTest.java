package io.springperf.web.core.retval.resolver;

import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.HttpMessageConverter;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WrapMessageConverterReturnValueResolverTest {

    @Mock
    HttpMessageConverter<String> converter;

    @Test
    void supportsReturnType_converterCanWrite_returnsTrue() throws Exception {
        when(converter.canWrite(String.class, null)).thenReturn(true);
        MethodParameter mp = param("stringReturn");

        WrapMessageConverterReturnValueResolver resolver = new WrapMessageConverterReturnValueResolver(converter);

        assertTrue(resolver.supportsReturnType(mp, null));
    }

    @Test
    void supportsReturnType_converterCannotWrite_returnsFalse() throws Exception {
        when(converter.canWrite(String.class, null)).thenReturn(false);
        MethodParameter mp = param("stringReturn");

        WrapMessageConverterReturnValueResolver resolver = new WrapMessageConverterReturnValueResolver(converter);

        assertFalse(resolver.supportsReturnType(mp, null));
    }

    @Test
    void supportsReturnValue_converterCanWrite_returnsTrue() {
        when(converter.canWrite(String.class, null)).thenReturn(true);
        WrapMessageConverterReturnValueResolver resolver = new WrapMessageConverterReturnValueResolver(converter);

        assertTrue(resolver.supportsReturnValue("test", null, null));
    }

    @Test
    void supportsReturnValue_converterCannotWrite_returnsFalse() {
        when(converter.canWrite(String.class, null)).thenReturn(false);
        WrapMessageConverterReturnValueResolver resolver = new WrapMessageConverterReturnValueResolver(converter);

        assertFalse(resolver.supportsReturnValue("test", null, null));
    }

    @Test
    void resolveReturnValue_delegatesToConverter() throws Exception {
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        WrapMessageConverterReturnValueResolver resolver = new WrapMessageConverterReturnValueResolver(converter);

        resolver.resolveReturnValue("test", null, null, response);

        verify(converter).write("test", null, response);
    }

    private MethodParameter param(String methodName) throws Exception {
        Method method = getClass().getMethod(methodName);
        return new MethodParameter(method, -1);
    }

    @SuppressWarnings("unused")
    public String stringReturn() { return null; }
}
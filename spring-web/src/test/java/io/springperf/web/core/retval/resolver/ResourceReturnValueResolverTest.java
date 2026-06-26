package io.springperf.web.core.retval.resolver;

import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResourceReturnValueResolverTest {

    ResourceReturnValueResolver resolver = new ResourceReturnValueResolver();

    @Mock
    WebServerHttpResponse response;

    @Test
    void supportsReturnType_resource_returnsTrue() throws Exception {
        assertTrue(resolver.supportsReturnType(returnParam("resourceReturn", Resource.class), null));
    }

    @Test
    void supportsReturnType_string_returnsFalse() throws Exception {
        assertFalse(resolver.supportsReturnType(returnParam("stringReturn", String.class), null));
    }

    @Test
    void supportsReturnValue_resource_returnsTrue() {
        assertTrue(resolver.supportsReturnValue(new ByteArrayResource(new byte[0]), null, null));
    }

    @Test
    void supportsReturnValue_string_returnsFalse() {
        assertFalse(resolver.supportsReturnValue("string", null, null));
    }

    @Test
    void resolveReturnValue_fileResource_callsWriteFile() throws Exception {
        File tmpFile = File.createTempFile("test", ".txt");
        try {
            Resource resource = mock(Resource.class);
            when(resource.isFile()).thenReturn(true);
            when(resource.getFile()).thenReturn(tmpFile);

            resolver.resolveReturnValue(resource, null, null, response);
            verify(response).writeFile(tmpFile);
        } finally {
            tmpFile.delete();
        }
    }

    @Test
    void resolveReturnValue_streamResource_callsWriteStream() throws Exception {
        Resource resource = mock(Resource.class);
        when(resource.isFile()).thenReturn(false);
        when(resource.getInputStream()).thenReturn(new ByteArrayResource(new byte[0]).getInputStream());

        resolver.resolveReturnValue(resource, null, null, response);
        verify(response).writeStream(any(InputStream.class));
    }

    private MethodParameter returnParam(String methodName, Class<?> returnType) throws Exception {
        Method method = getClass().getMethod(methodName);
        return new MethodParameter(method, -1);
    }

    @SuppressWarnings("unused")
    public Resource resourceReturn() { return null; }
    @SuppressWarnings("unused")
    public String stringReturn() { return null; }
}
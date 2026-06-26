package io.springperf.web.core.retval.resolver;

import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FileReturnValueResolverTest {

    FileReturnValueResolver resolver = new FileReturnValueResolver();

    @Mock
    WebServerHttpResponse response;

    @Test
    void supportsReturnType_file_returnsTrue() throws Exception {
        assertTrue(resolver.supportsReturnType(returnParam("fileReturn", File.class), null));
    }

    @Test
    void supportsReturnType_path_returnsTrue() throws Exception {
        assertTrue(resolver.supportsReturnType(returnParam("pathReturn", Path.class), null));
    }

    @Test
    void supportsReturnType_string_returnsFalse() throws Exception {
        assertFalse(resolver.supportsReturnType(returnParam("stringReturn", String.class), null));
    }

    @Test
    void supportsReturnValue_file_returnsTrue() {
        assertTrue(resolver.supportsReturnValue(new File("/tmp/test"), null, null));
    }

    @Test
    void supportsReturnValue_path_returnsTrue() {
        assertTrue(resolver.supportsReturnValue(Paths.get("/tmp/test"), null, null));
    }

    @Test
    void supportsReturnValue_string_returnsFalse() {
        assertFalse(resolver.supportsReturnValue("string", null, null));
    }

    @Test
    void resolveReturnValue_file_callsWriteFile() throws Exception {
        File file = new File("/tmp/test.txt");
        resolver.resolveReturnValue(file, null, null, response);
        verify(response).writeFile(file);
    }

    @Test
    void resolveReturnValue_path_convertsToFileAndWrites() throws Exception {
        Path path = Paths.get("/tmp/test.txt");
        resolver.resolveReturnValue(path, null, null, response);
        verify(response).writeFile(path.toFile());
    }

    /** Create MethodParameter for return type (index -1) */
    private MethodParameter returnParam(String methodName, Class<?> returnType) throws Exception {
        Method method = getClass().getMethod(methodName);
        return new MethodParameter(method, -1);
    }

    @SuppressWarnings("unused")
    public File fileReturn() { return null; }
    @SuppressWarnings("unused")
    public Path pathReturn() { return null; }
    @SuppressWarnings("unused")
    public String stringReturn() { return null; }
}
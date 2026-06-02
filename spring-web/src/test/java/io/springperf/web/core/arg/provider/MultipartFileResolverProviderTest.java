package io.springperf.web.core.arg.provider;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class MultipartFileResolverProviderTest {

    MultipartFileResolverProvider provider = new MultipartFileResolverProvider();

    @Test
    void supports_multipartFileType_returnsTrue() throws Exception {
        Method method = getClass().getMethod("fileParam", MultipartFile.class);
        MethodParameter mp = new MethodParameter(method, 0);
        assertTrue(provider.supports(mp, null));
    }

    @Test
    void supports_multipartFileArrayType_returnsTrue() throws Exception {
        Method method = getClass().getMethod("fileArrayParam", MultipartFile[].class);
        MethodParameter mp = new MethodParameter(method, 0);
        assertTrue(provider.supports(mp, null));
    }

    @Test
    void supports_nonMultipartFileType_returnsFalse() throws Exception {
        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        assertFalse(provider.supports(mp, null));
    }

    @Test
    void order_isNegative10000() {
        assertEquals(-10000, provider.getOrder());
    }

    @Test
    void supportType_isMultipartFile() {
        assertEquals(MultipartFile.class, provider.supportType());
    }

    @Test
    void getMultiValueMapResolver_isNotNull() {
        assertNotNull(provider.getMultiValueMapResolver());
    }

    @SuppressWarnings("unused")
    public void fileParam(MultipartFile file) {}

    @SuppressWarnings("unused")
    public void fileArrayParam(MultipartFile[] files) {}

    @SuppressWarnings("unused")
    public void stringParam(String s) {}
}

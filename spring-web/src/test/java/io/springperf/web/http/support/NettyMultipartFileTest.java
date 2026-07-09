package io.springperf.web.http.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NettyMultipartFileTest {

    @Test
    void buildContentDisposition_normalNameAndFilename() {
        String result = NettyMultipartFile.buildContentDisposition("field1", "test.txt");
        assertEquals("form-data; name=\"field1\"; filename=\"test.txt\"", result);
    }

    @Test
    void buildContentDisposition_nameWithCrLf_removesCrLf() {
        String result = NettyMultipartFile.buildContentDisposition("field\r\n1", "test.txt");
        assertFalse(result.contains("\r"), "name 中的 CR 应被移除");
        assertFalse(result.contains("\n"), "name 中的 LF 应被移除");
        assertEquals("form-data; name=\"field1\"; filename=\"test.txt\"", result);
    }

    @Test
    void buildContentDisposition_filenameWithCrLf_removesCrLf() {
        String result = NettyMultipartFile.buildContentDisposition("field1", "test\r\n.txt");
        assertFalse(result.contains("\r"), "filename 中的 CR 应被移除");
        assertFalse(result.contains("\n"), "filename 中的 LF 应被移除");
        assertEquals("form-data; name=\"field1\"; filename=\"test.txt\"", result);
    }

    @Test
    void buildContentDisposition_filenameWithCrOnly_removesCr() {
        String result = NettyMultipartFile.buildContentDisposition("field1", "test\r.txt");
        assertEquals("form-data; name=\"field1\"; filename=\"test.txt\"", result);
    }

    @Test
    void buildContentDisposition_filenameWithLfOnly_removesLf() {
        String result = NettyMultipartFile.buildContentDisposition("field1", "test\n.txt");
        assertEquals("form-data; name=\"field1\"; filename=\"test.txt\"", result);
    }

    @Test
    void buildContentDisposition_filenameNull() {
        String result = NettyMultipartFile.buildContentDisposition("field1", null);
        assertFalse(result.contains("filename"), "filename 为 null 时不应包含 filename 部分");
        assertEquals("form-data; name=\"field1\"", result);
    }

    @Test
    void buildContentDisposition_multipleCrLfSequences() {
        String result = NettyMultipartFile.buildContentDisposition("a\r\nb\r\nc", "x\r\ny\r\nz.txt");
        assertFalse(result.contains("\r"));
        assertFalse(result.contains("\n"));
        assertEquals("form-data; name=\"abc\"; filename=\"xyz.txt\"", result);
    }

    @ParameterizedTest
    @CsvSource({
        "normal, test.txt",
        "'', test.txt",
        "' ', test.txt",
        "field1, ''"
    })
    void buildContentDisposition_variousInputs_noCrLfInOutput(String name, String filename) {
        String result = NettyMultipartFile.buildContentDisposition(name, filename);
        assertFalse(result.contains("\r"), "输出不应含 CR: name=" + name + " filename=" + filename);
        assertFalse(result.contains("\n"), "输出不应含 LF: name=" + name + " filename=" + filename);
    }

    @Test
    void buildContentDisposition_headerInjectionCrLf_removed() {
        String malicious = "safe.txt\r\nX-Injected-Header: malicious\r\n";
        String result = NettyMultipartFile.buildContentDisposition("field", malicious);
        assertFalse(result.contains("\r"), "CR 注入字符应被移除");
        assertFalse(result.contains("\n"), "LF 注入字符应被移除");
        assertFalse(result.contains("safe.txt\r\nX-Injected-Header"), "CRLF 不应出现在输出中");
        assertEquals("form-data; name=\"field\"; filename=\"safe.txtX-Injected-Header: malicious\"", result);
    }
}
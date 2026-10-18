package io.springperf.web.http.support;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.multipart.MemoryFileUpload;

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
    @CsvSource({ "normal, test.txt", "'', test.txt", "' ', test.txt", "field1, ''" })
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

    @Test
    void getInputStream_close_doesNotReleaseFileUploadContent() throws IOException {
        ByteBuf content = Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8);
        MemoryFileUpload upload = new MemoryFileUpload("f", "a.txt", "text/plain", null, StandardCharsets.UTF_8,
                content.readableBytes());
        upload.setContent(content);
        NettyMultipartFile file = new NettyMultipartFile(upload);

        try (InputStream in = file.getInputStream()) {
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        }
        // 所有权归 HttpPostRequestDecoder.destroy()：关闭流只是停止读取。
        // 修复前单参 ByteBufInputStream 的 close() 会 release 底层 buf，业务侧按惯例
        // try-with-resources 后 data 归零，随后 destroy() 的无守卫 release 抛
        // IllegalReferenceCountException，release() 的兜底循环被 catch 吞掉（P0）。
        assertEquals(1, content.refCnt(), "关闭流不得释放 part content");
        // duplicate() 隔离 readerIndex：读流不得推进 data 自己的 readerIndex
        assertEquals("hello", new String(upload.get(), StandardCharsets.UTF_8));
    }

    @Test
    void getBody_close_doesNotReleaseFileUploadContent() throws IOException {
        ByteBuf content = Unpooled.copiedBuffer("world", StandardCharsets.UTF_8);
        MemoryFileUpload upload = new MemoryFileUpload("f", "b.txt", "text/plain", null, StandardCharsets.UTF_8,
                content.readableBytes());
        upload.setContent(content);
        NettyMultipartFile file = new NettyMultipartFile(upload);

        try (InputStream in = file.getBody()) {
            assertArrayEquals("world".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        }
        assertEquals(1, content.refCnt(), "关闭流不得释放 part content");
        assertEquals("world", new String(upload.get(), StandardCharsets.UTF_8));
    }
}

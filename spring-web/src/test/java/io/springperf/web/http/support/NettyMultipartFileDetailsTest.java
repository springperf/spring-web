package io.springperf.web.http.support;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.multipart.FileUpload;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NettyMultipartFileDetailsTest {

    private FileUpload mockUpload() {
        FileUpload upload = mock(FileUpload.class);
        when(upload.length()).thenReturn(5L);
        return upload;
    }

    @Test
    void getName_delegates() {
        FileUpload upload = mockUpload();
        when(upload.getName()).thenReturn("file1");
        NettyMultipartFile file = new NettyMultipartFile(upload);
        assertEquals("file1", file.getName());
    }

    @Test
    void getOriginalFilename_andSubmittedFileName_delegate() {
        FileUpload upload = mockUpload();
        when(upload.getFilename()).thenReturn("test.txt");
        NettyMultipartFile file = new NettyMultipartFile(upload);
        assertEquals("test.txt", file.getOriginalFilename());
        assertEquals("test.txt", file.getSubmittedFileName());
    }

    @Test
    void getContentType_delegates() {
        FileUpload upload = mockUpload();
        when(upload.getContentType()).thenReturn("text/plain");
        NettyMultipartFile file = new NettyMultipartFile(upload);
        assertEquals("text/plain", file.getContentType());
    }

    @Test
    void isEmpty_true_whenLengthZero() {
        FileUpload upload = mock(FileUpload.class);
        when(upload.length()).thenReturn(0L);
        NettyMultipartFile file = new NettyMultipartFile(upload);
        assertTrue(file.isEmpty());
    }

    @Test
    void isEmpty_false_whenLengthPositive() {
        NettyMultipartFile file = new NettyMultipartFile(mockUpload());
        assertFalse(file.isEmpty());
    }

    @Test
    void getSize_returnsLength() {
        NettyMultipartFile file = new NettyMultipartFile(mockUpload());
        assertEquals(5L, file.getSize());
    }

    @Test
    void getBytes_returnsUploadBytes() throws Exception {
        FileUpload upload = mockUpload();
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        when(upload.get()).thenReturn(data);
        NettyMultipartFile file = new NettyMultipartFile(upload);
        assertArrayEquals(data, file.getBytes());
    }

    @Test
    void getInputStream_readsContent() throws Exception {
        FileUpload upload = mockUpload();
        ByteBuf buf = Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8);
        when(upload.content()).thenReturn(buf);
        NettyMultipartFile file = new NettyMultipartFile(upload);
        try (InputStream in = file.getInputStream()) {
            byte[] data = new byte[5];
            int n = in.read(data);
            assertEquals(5, n);
            assertEquals("hello", new String(data, StandardCharsets.UTF_8));
        } finally {
            buf.release();
        }
    }

    @Test
    void getBody_readsByteBuf() throws Exception {
        FileUpload upload = mockUpload();
        ByteBuf buf = Unpooled.copiedBuffer("body", StandardCharsets.UTF_8);
        when(upload.getByteBuf()).thenReturn(buf);
        NettyMultipartFile file = new NettyMultipartFile(upload);
        try (InputStream in = file.getBody()) {
            byte[] data = new byte[4];
            assertEquals(4, in.read(data));
            assertEquals("body", new String(data, StandardCharsets.UTF_8));
        } finally {
            buf.release();
        }
    }

    @Test
    void transferTo_renames() throws Exception {
        FileUpload upload = mockUpload();
        File dest = File.createTempFile("perf-multipart", ".tmp");
        try {
            NettyMultipartFile file = new NettyMultipartFile(upload);
            file.transferTo(dest);
            verify(upload).renameTo(dest);
        } finally {
            dest.delete();
        }
    }

    @Test
    void getHeaders_buildsDispositionTypeAndLength() {
        FileUpload upload = mockUpload();
        when(upload.getName()).thenReturn("field");
        when(upload.getFilename()).thenReturn("a.txt");
        when(upload.getContentType()).thenReturn("text/plain");
        when(upload.getContentTransferEncoding()).thenReturn("binary");
        NettyMultipartFile file = new NettyMultipartFile(upload);

        HttpHeaders headers = file.getHeaders();
        assertEquals("form-data; name=\"field\"; filename=\"a.txt\"",
                headers.getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals("text/plain", headers.getFirst(HttpHeaders.CONTENT_TYPE));
        assertEquals(5L, headers.getContentLength());
        assertEquals("binary", headers.getFirst("Content-Transfer-Encoding"));
    }

    @Test
    void getHeaders_noContentType_noTransferEncoding() {
        FileUpload upload = mockUpload();
        when(upload.getName()).thenReturn("field");
        when(upload.getFilename()).thenReturn(null);
        when(upload.getContentType()).thenReturn(null);
        when(upload.getContentTransferEncoding()).thenReturn(null);
        NettyMultipartFile file = new NettyMultipartFile(upload);

        HttpHeaders headers = file.getHeaders();
        assertEquals("form-data; name=\"field\"", headers.getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertNull(headers.get(HttpHeaders.CONTENT_TYPE));
        assertNull(headers.get("Content-Transfer-Encoding"));
    }

    @Test
    void getHeaders_cachedAfterFirstCall() {
        FileUpload upload = mockUpload();
        when(upload.getName()).thenReturn("field");
        when(upload.length()).thenReturn(3L);
        NettyMultipartFile file = new NettyMultipartFile(upload);
        assertSame(file.getHeaders(), file.getHeaders());
        verify(upload, times(1)).length();
    }

    @Test
    void hasBody_true_whenReadable() throws IOException {
        FileUpload upload = mockUpload();
        ByteBuf buf = Unpooled.copiedBuffer("x", StandardCharsets.UTF_8);
        when(upload.getByteBuf()).thenReturn(buf);
        NettyMultipartFile file = new NettyMultipartFile(upload);
        assertTrue(file.hasBody());
        buf.release();
    }

    @Test
    void hasBody_false_whenNotReadable() throws IOException {
        FileUpload upload = mockUpload();
        ByteBuf buf = Unpooled.buffer();
        when(upload.getByteBuf()).thenReturn(buf);
        NettyMultipartFile file = new NettyMultipartFile(upload);
        assertFalse(file.hasBody());
        buf.release();
    }

    @Test
    void buildContentDisposition_sanitizesCrLfInNameAndFilename() {
        // sanitize 移除 \r\n 但不补空格
        assertEquals("form-data; name=\"ab\"; filename=\"cd.txt\"",
                NettyMultipartFile.buildContentDisposition("a\r\nb", "c\r\nd.txt"));
    }
}

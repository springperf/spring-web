package io.springperf.web.http.support;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.multipart.Attribute;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NettyAttributeMessageTest {

    @Test
    void getName_delegatesToAttribute() {
        Attribute attribute = mock(Attribute.class);
        when(attribute.getName()).thenReturn("field1");
        NettyAttributeMessage message = new NettyAttributeMessage(attribute);
        assertEquals("field1", message.getName());
    }

    @Test
    void getSize_returnsAttributeLength() {
        Attribute attribute = mock(Attribute.class);
        when(attribute.length()).thenReturn(5L);
        NettyAttributeMessage message = new NettyAttributeMessage(attribute);
        assertEquals(5L, message.getSize());
    }

    @Test
    void getSubmittedFileName_isNull() {
        NettyAttributeMessage message = new NettyAttributeMessage(mock(Attribute.class));
        assertNull(message.getSubmittedFileName());
    }

    @Test
    void getValue_delegates() throws IOException {
        Attribute attribute = mock(Attribute.class);
        when(attribute.getValue()).thenReturn("value1");
        NettyAttributeMessage message = new NettyAttributeMessage(attribute);
        assertEquals("value1", message.getValue());
    }

    @Test
    void getBody_returnsByteBufInputStream() throws IOException {
        ByteBuf buf = Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8);
        Attribute attribute = mock(Attribute.class);
        when(attribute.getByteBuf()).thenReturn(buf);
        NettyAttributeMessage message = new NettyAttributeMessage(attribute);
        try (InputStream in = message.getBody()) {
            byte[] data = new byte[5];
            int n = in.read(data);
            assertEquals(5, n);
            assertEquals("hello", new String(data, StandardCharsets.UTF_8));
        } finally {
            buf.release();
        }
    }

    @Test
    void getHeaders_buildsContentDispositionAndLength() {
        Attribute attribute = mock(Attribute.class);
        when(attribute.getName()).thenReturn("field1");
        when(attribute.length()).thenReturn(3L);
        NettyAttributeMessage message = new NettyAttributeMessage(attribute);

        HttpHeaders headers = message.getHeaders();
        assertEquals("form-data; name=\"field1\"",
                headers.getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals(3L, headers.getContentLength());
    }

    @Test
    void getHeaders_negativeLength_omitsContentLength() {
        Attribute attribute = mock(Attribute.class);
        when(attribute.getName()).thenReturn("field1");
        when(attribute.length()).thenReturn(-1L);
        NettyAttributeMessage message = new NettyAttributeMessage(attribute);

        HttpHeaders headers = message.getHeaders();
        assertEquals("form-data; name=\"field1\"",
                headers.getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertNull(headers.get(HttpHeaders.CONTENT_LENGTH));
    }

    @Test
    void getHeaders_cachedAfterFirstCall() {
        Attribute attribute = mock(Attribute.class);
        when(attribute.getName()).thenReturn("field1");
        when(attribute.length()).thenReturn(3L);
        NettyAttributeMessage message = new NettyAttributeMessage(attribute);

        HttpHeaders first = message.getHeaders();
        HttpHeaders second = message.getHeaders();
        assertSame(first, second);
        verify(attribute, times(1)).length();
    }

    @Test
    void hasBody_true_whenByteBufReadable() throws IOException {
        ByteBuf buf = Unpooled.copiedBuffer("x", StandardCharsets.UTF_8);
        Attribute attribute = mock(Attribute.class);
        when(attribute.getByteBuf()).thenReturn(buf);
        NettyAttributeMessage message = new NettyAttributeMessage(attribute);
        assertTrue(message.hasBody());
        buf.release();
    }

    @Test
    void hasBody_false_whenByteBufNotReadable() throws IOException {
        ByteBuf buf = Unpooled.buffer();
        Attribute attribute = mock(Attribute.class);
        when(attribute.getByteBuf()).thenReturn(buf);
        NettyAttributeMessage message = new NettyAttributeMessage(attribute);
        assertFalse(message.hasBody());
        buf.release();
    }
}

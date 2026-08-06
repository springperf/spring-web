package io.springperf.web.core.async.stream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TextStreamEmitterTest {

    private final TextStreamEmitter emitter = new TextStreamEmitter();

    @Test
    void extendResponse_setsContentTypeAndCacheControl() {
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        when(response.getHeaders()).thenReturn(headers);

        emitter.extendResponse(response);

        assertEquals(MediaType.TEXT_PLAIN, headers.getContentType());
        assertEquals("no-cache", headers.getCacheControl());
    }

    @Test
    void extendResponse_doesNotOverrideExistingContentType() {
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        when(response.getHeaders()).thenReturn(headers);

        emitter.extendResponse(response);

        assertEquals(MediaType.APPLICATION_JSON, headers.getContentType());
        assertEquals("no-cache", headers.getCacheControl());
    }

    @Test
    void encode_null_returnsNewline() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        emitter.encode(null, baos);
        assertEquals("\n", new String(baos.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void encode_string_returnsStringWithNewline() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        emitter.encode("hello", baos);
        assertEquals("hello\n", new String(baos.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void encode_emptyString_returnsNewline() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        emitter.encode("", baos);
        assertEquals("\n", new String(baos.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void getMaxFlushBytes_returns32768() {
        assertEquals(32768, emitter.getMaxFlushBytes());
    }

    @Test
    void constructor_withTimeout() {
        TextStreamEmitter timeoutEmitter = new TextStreamEmitter(5000L);
        assertNotNull(timeoutEmitter);
    }
}
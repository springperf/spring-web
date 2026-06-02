package io.springperf.web.core.async.stream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
    void encodeToString_null_returnsNewline() {
        CharSequence result = emitter.encodeToString(null);
        assertEquals("\n", result.toString());
    }

    @Test
    void encodeToString_string_returnsStringWithNewline() {
        CharSequence result = emitter.encodeToString("hello");
        assertEquals("hello\n", result.toString());
    }

    @Test
    void encodeToString_emptyString_returnsNewline() {
        CharSequence result = emitter.encodeToString("");
        assertEquals("\n", result.toString());
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

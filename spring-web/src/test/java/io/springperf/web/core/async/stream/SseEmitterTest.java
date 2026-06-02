package io.springperf.web.core.async.stream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.ServerHttpResponse;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SseEmitterTest {

    private final SseEmitter emitter = new SseEmitter();

    @Test
    void extendResponse_setsContentTypeAndCacheControl() {
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        when(response.getHeaders()).thenReturn(headers);

        emitter.extendResponse(response);

        assertEquals(MediaType.TEXT_EVENT_STREAM, headers.getContentType());
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
    void encodeToString_withServerSentEvent_allFields() {
        ServerSentEvent<Object> event = ServerSentEvent.builder()
                .id("1")
                .event("message")
                .retry(Duration.ofMillis(3000))
                .comment("test comment")
                .data("hello")
                .build();

        CharSequence result = emitter.encodeToString(event);

        assertTrue(result.toString().contains("id:1"));
        assertTrue(result.toString().contains("event:message"));
        assertTrue(result.toString().contains("retry:3000"));
        assertTrue(result.toString().contains(":test comment"));
        assertTrue(result.toString().contains("data:hello"));
        assertTrue(result.toString().endsWith("\n\n"));
    }

    @Test
    void encodeToString_withPlainData() {
        String data = "hello world";

        CharSequence result = emitter.encodeToString(data);

        String str = result.toString();
        assertTrue(str.contains("data:hello world"));
        assertTrue(str.endsWith("\n\n"));
    }

    @Test
    void encodeToString_withNullData() {
        CharSequence result = emitter.encodeToString((Object) null);

        assertNotNull(result);
        assertEquals("\n", result.toString());
    }

    @Test
    void encodeToString_multilineData() {
        String data = "line1\nline2";

        CharSequence result = emitter.encodeToString(data);

        String str = result.toString();
        assertTrue(str.contains("data:line1"));
        assertTrue(str.contains("data:line2"));
    }

    @Test
    void encodeToString_withServerSentEvent_onlyData() {
        ServerSentEvent<Object> event = ServerSentEvent.builder().data("hello").build();

        CharSequence result = emitter.encodeToString(event);

        assertFalse(result.toString().contains("id:"));
        assertFalse(result.toString().contains("event:"));
        assertFalse(result.toString().contains("retry:"));
        assertTrue(result.toString().contains("data:hello"));
    }

    @Test
    void encodeToString_withCommentOnly() {
        ServerSentEvent<Object> event = ServerSentEvent.builder()
                .comment("keepalive")
                .data("ping")
                .build();

        CharSequence result = emitter.encodeToString(event);

        String str = result.toString();
        assertTrue(str.contains(":keepalive"));
        assertTrue(str.contains("data:ping"));
    }

    @Test
    void getMaxFlushBytes_returns4096() {
        assertEquals(4096, emitter.getMaxFlushBytes());
    }

    @Test
    void writeField_appendsField() {
        StringBuilder sb = new StringBuilder();
        emitter.writeField("test", "value", sb);
        assertEquals("test:value\n", sb.toString());
    }

    @Test
    void writeField_withNumberValue_appendsField() {
        StringBuilder sb = new StringBuilder();
        emitter.writeField("retry", 3000L, sb);
        assertEquals("retry:3000\n", sb.toString());
    }

    @Test
    void constructor_withTimeout() {
        SseEmitter timeoutEmitter = new SseEmitter(5000L);
        assertNotNull(timeoutEmitter);
    }
}

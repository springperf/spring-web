package io.springperf.web.core.async.stream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.ServerHttpResponse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void encode_withServerSentEvent_allFields() throws Exception {
        ServerSentEvent<Object> event = ServerSentEvent.builder()
                .id("1")
                .event("message")
                .retry(Duration.ofMillis(3000))
                .comment("test comment")
                .data("hello")
                .build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        emitter.encode(event, baos);
        String result = baos.toString(StandardCharsets.UTF_8);

        assertTrue(result.contains("id:1"));
        assertTrue(result.contains("event:message"));
        assertTrue(result.contains("retry:3000"));
        assertTrue(result.contains(":test comment"));
        assertTrue(result.contains("data:hello"));
        assertTrue(result.endsWith("\n\n"));
    }

    @Test
    void encode_withPlainData() throws Exception {
        String data = "hello world";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        emitter.encode(data, baos);
        String result = baos.toString(StandardCharsets.UTF_8);

        assertTrue(result.contains("data:hello world"));
        assertTrue(result.endsWith("\n\n"));
    }

    @Test
    void encode_withNullData() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        emitter.encode((Object) null, baos);

        String result = baos.toString(StandardCharsets.UTF_8);
        assertEquals("\n", result);
    }

    @Test
    void encode_multilineData() throws Exception {
        String data = "line1\nline2";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        emitter.encode(data, baos);
        String result = baos.toString(StandardCharsets.UTF_8);

        assertTrue(result.contains("data:line1"));
        assertTrue(result.contains("data:line2"));
    }

    @Test
    void encode_withServerSentEvent_onlyData() throws Exception {
        ServerSentEvent<Object> event = ServerSentEvent.builder().data("hello").build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        emitter.encode(event, baos);
        String result = baos.toString(StandardCharsets.UTF_8);

        assertFalse(result.contains("id:"));
        assertFalse(result.contains("event:"));
        assertFalse(result.contains("retry:"));
        assertTrue(result.contains("data:hello"));
    }

    @Test
    void encode_withCommentOnly() throws Exception {
        ServerSentEvent<Object> event = ServerSentEvent.builder()
                .comment("keepalive")
                .data("ping")
                .build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        emitter.encode(event, baos);
        String result = baos.toString(StandardCharsets.UTF_8);

        assertTrue(result.contains(":keepalive"));
        assertTrue(result.contains("data:ping"));
    }

    @Test
    void getMaxFlushBytes_returns4096() {
        assertEquals(4096, emitter.getMaxFlushBytes());
    }

    @Test
    void constructor_withTimeout() {
        SseEmitter timeoutEmitter = new SseEmitter(5000L);
        assertNotNull(timeoutEmitter);
    }
}
package io.springperf.web.core.async.stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.ServerHttpResponse;

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
        ServerSentEvent<Object> event = ServerSentEvent.builder().id("1").event("message")
                .retry(Duration.ofMillis(3000)).comment("test comment").data("hello").build();

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
        // SSE 规范要求事件以 data: 字段开头、以空行结束。旧实现只写裸 \n，
        // 客户端既取不到 data 字段也判定不出事件边界，等同丢事件。
        assertEquals("data:\n\n", result);
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
        ServerSentEvent<Object> event = ServerSentEvent.builder().comment("keepalive").data("ping").build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        emitter.encode(event, baos);
        String result = baos.toString(StandardCharsets.UTF_8);

        assertTrue(result.contains(":keepalive"));
        assertTrue(result.contains("data:ping"));
    }

    /**
     * 边界（记录用）：{@code id} / {@code event} 里的 LF 由 Spring 的 {@code ServerSentEvent} **在构造期就拒绝**
     * （{@code illegal character '\n' or '\r'}），所以本编码器不为它们做续行处理； 而 {@code data} 是自由文本、builder 不做校验，因此 {@code data} 与
     * {@code comment} 必须自行按 LF 续行。
     * <p>
     * 此用例把这条分工钉住：读代码时"同一方法里 data 续行、id 不续行"看着像漏改，实际是**契约分工**。
     * </p>
     */
    @Test
    void serverSentEvent_rejectsNewlineInIdOrEvent_atConstruction() {
        assertThrows(IllegalArgumentException.class, () -> ServerSentEvent.builder().id("a\nb"),
                "id 里的 LF 由 Spring 在构造期拒绝 —— 这正是编码器可以信任 id/event 的原因");
        assertThrows(IllegalArgumentException.class, () -> ServerSentEvent.builder().event("x\ny"));
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

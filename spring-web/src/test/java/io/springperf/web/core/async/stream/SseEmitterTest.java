package io.springperf.web.core.async.stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

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
     * 边界：{@code id} / {@code event} 里的换行**绝不能被写进帧**——一个裸 LF 会伪造出后续 SSE 字段（协议注入）； 而 {@code data} 是自由文本，按 LF 续行即可。
     * <p>
     * 拦截位置随 Spring 版本变化：6.2 的某个补丁版本起，{@code ServerSentEvent} builder 在构造期就抛 {@code illegal character}；Boot 3.2~3.4 对应的
     * Spring 6.1 / 6.2 早期不抛。因此本用例只钉住"必须被拒绝" 这个契约，不钉住"在哪一层拒绝"——编码器自己兜底是硬要求，见 {@code SseEmitter#requireNoLineBreak}。
     * </p>
     */
    @Test
    void serverSentEvent_rejectsNewlineInIdOrEvent() throws IOException {
        assertRejectedBeforeWriting(() -> ServerSentEvent.builder().id("a\nb"));
        assertRejectedBeforeWriting(() -> ServerSentEvent.builder().event("x\ny"));
    }

    private void assertRejectedBeforeWriting(Supplier<ServerSentEvent.Builder<Object>> builder) throws IOException {
        ServerSentEvent<Object> sse;
        try {
            sse = builder.get().build();
        } catch (IllegalArgumentException rejectedAtConstruction) {
            return; // 较新的 Spring 在构造期就拦下，编码器无需再拒
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class, () -> emitter.encode(sse, out), "编码器必须在写出前拒绝含换行的 id/event");
        assertEquals(0, out.size(), "拒绝必须发生在写出任何字节之前");
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

package org.springframework.web.servlet.mvc.method.annotation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SseEmitterTest {

    @Mock ServerHttpResponse serverHttpResponse;

    @Test
    void defaultConstructor_hasNoTimeout() {
        SseEmitter emitter = new SseEmitter();
        assertNull(emitter.getTimeout());
    }

    @Test
    void timeoutConstructor_createsEmitter() {
        SseEmitter emitter = new SseEmitter(5000L);
        assertNotNull(emitter);
        assertEquals(5000L, emitter.getTimeout().longValue());
    }

    @Test
    void extendResponse_noContentType_setsEventStream() {
        HttpHeaders headers = new HttpHeaders();
        when(serverHttpResponse.getHeaders()).thenReturn(headers);

        SseEmitter emitter = new SseEmitter();
        emitter.extendResponse(serverHttpResponse);

        assertEquals(MediaType.TEXT_EVENT_STREAM, headers.getContentType());
    }

    @Test
    void toString_containsIdentity() {
        SseEmitter emitter = new SseEmitter();
        assertTrue(emitter.toString().contains("SseEmitter"));
    }

    @Test
    void event_staticMethod_returnsBuilder() {
        assertNotNull(SseEmitter.event());
    }

    @Test
    void eventBuilder_build_generatesDataWithMediaType() {
        SseEmitter.SseEventBuilder builder = SseEmitter.event()
                .id("1")
                .name("update")
                .data("payload");

        Set<SseEmitter.DataWithMediaType> result = builder.build();
        assertFalse(result.isEmpty());
        assertEquals(MediaType.valueOf("text/plain;charset=UTF-8"), result.iterator().next().getMediaType(),
                "SSE 文本行应以 text/plain;charset=UTF-8 输出");
        assertTrue(result.stream().anyMatch(part -> "payload".equals(part.getData())),
                "data() 的业务对象应原样入集，实际: " + concat(result));
    }

    @Test
    void eventBuilder_id_appendsIdLine() {
        Set<SseEmitter.DataWithMediaType> result = SseEmitter.event().id("123").build();
        assertFalse(result.isEmpty());
        assertTrue(result.iterator().next().getData().toString().contains("id:123"));
    }

    @Test
    void eventBuilder_name_appendsEventLine() {
        Set<SseEmitter.DataWithMediaType> result = SseEmitter.event().name("user-update").build();
        assertFalse(result.isEmpty());
        String content = result.iterator().next().getData().toString();
        assertTrue(content.contains("event:user-update"),
                "应生成 event: 行，实际: " + content);
    }

    @Test
    void eventBuilder_reconnectTime_appendsRetryLine() {
        Set<SseEmitter.DataWithMediaType> result = SseEmitter.event().reconnectTime(3000L).build();
        assertFalse(result.isEmpty());
        assertTrue(result.iterator().next().getData().toString().contains("retry:3000"));
    }

    @Test
    void eventBuilder_comment_appendsCommentLine() {
        Set<SseEmitter.DataWithMediaType> result = SseEmitter.event().comment("heartbeat").build();
        assertFalse(result.isEmpty());
        String content = result.iterator().next().getData().toString();
        assertTrue(content.contains(":heartbeat"),
                "应生成注释行(:heartbeat)，实际: " + content);
    }

    @Test
    void eventBuilder_data_only() {
        Set<SseEmitter.DataWithMediaType> result = SseEmitter.event().data("hello").build();
        assertFalse(result.isEmpty());
        // data() 会把 "data:" 前缀与 object 分成多个 DataWithMediaType，需拼接全部内容校验
        String content = result.stream()
                .map(d -> d.getData().toString())
                .reduce("", String::concat);
        assertTrue(content.contains("data:"), "应含 data: 前缀，实际: " + content);
        assertTrue(content.contains("hello"), "应含数据内容，实际: " + content);
    }

    @Test
    void eventBuilder_emptyBuild_returnsEmpty() {
        Set<SseEmitter.DataWithMediaType> result = SseEmitter.event().build();
        assertTrue(result.isEmpty());
    }

    @Test
    void eventBuilder_chainedBuild_containsAllParts() {
        Set<SseEmitter.DataWithMediaType> result = SseEmitter.event()
                .id("1")
                .name("msg")
                .reconnectTime(1000L)
                .comment("test")
                .data("hello")
                .build();

        String all = concat(result);
        assertTrue(all.contains("id:1"), () -> "缺少 id 行，实际: " + all);
        assertTrue(all.contains("event:msg"), () -> "缺少 event 行，实际: " + all);
        assertTrue(all.contains("retry:1000"), () -> "缺少 retry 行，实际: " + all);
        assertTrue(all.contains(":test"), () -> "缺少 comment 行，实际: " + all);
        assertTrue(all.contains("data:") && all.contains("hello"), () -> "缺少 data 行，实际: " + all);
    }

    @Test
    void eventBuilder_multipleData_keepsEveryPayload() {
        Set<SseEmitter.DataWithMediaType> result = SseEmitter.event().data("first").data("second").build();

        String all = concat(result);
        assertTrue(all.contains("first"), () -> "第一段数据丢失，实际: " + all);
        assertTrue(all.contains("second"), () -> "第二段数据丢失，实际: " + all);
    }

    private static String concat(Set<SseEmitter.DataWithMediaType> parts) {
        return parts.stream().map(d -> String.valueOf(d.getData())).reduce("", String::concat);
    }

    @Test
    void eventBuilder_buildContent_containsSseFormatting() {
        Set<SseEmitter.DataWithMediaType> result = SseEmitter.event()
                .id("1")
                .name("msg")
                .data("hello")
                .build();

        String allContent = result.stream()
                .map(d -> d.getData().toString())
                .reduce("", String::concat);

        assertTrue(allContent.contains("id:1"));
        assertTrue(allContent.contains("event:msg"));
    }
}
package io.springperf.web.core.async.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.springperf.web.json.JsonConverter;

@ExtendWith(MockitoExtension.class)
class SseJsonEmitterTest {

    @Mock
    JsonConverter jsonConverter;

    @Test
    void encode_withObject_usesJsonConverter() throws Exception {
        Object data = new Object();
        byte[] jsonBytes = "{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8);
        doAnswer(invocation -> {
            ((OutputStream) invocation.getArgument(0)).write(jsonBytes);
            return null;
        }).when(jsonConverter).toJson(any(OutputStream.class), eq(data));

        SseJsonEmitter emitter = new SseJsonEmitter(jsonConverter);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        emitter.encode(data, baos);

        String output = baos.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("data:{\"key\":\"value\"}"));
        verify(jsonConverter).toJson(any(OutputStream.class), eq(data));
    }

    /**
     * 转换器是否**美化输出**决定 JSON 里有没有裸 LF，而 SSE 字段值不得含 LF —— 客户端按规范会**丢弃整条事件** （静默丢数据）。基类对非 {@code CharSequence}
     * 数据不做续行，所以这一条必须由本子类自己保证。
     */
    @Test
    void encode_jsonContainingNewline_usesDataContinuation() throws Exception {
        Object data = new Object();
        byte[] prettyJson = "{\n  \"key\":\"value\"\n}".getBytes(StandardCharsets.UTF_8);
        doAnswer(invocation -> {
            ((OutputStream) invocation.getArgument(0)).write(prettyJson);
            return null;
        }).when(jsonConverter).toJson(any(OutputStream.class), eq(data));

        SseJsonEmitter emitter = new SseJsonEmitter(jsonConverter);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        emitter.encode(data, baos);

        assertEquals("data:{\ndata:  \"key\":\"value\"\ndata:}\n\n", baos.toString(StandardCharsets.UTF_8),
                "JSON 内的裸 LF 必须转成 data: 续行，否则客户端会丢弃整条事件");
    }

    @Test
    void constructor_withTimeout() {
        SseJsonEmitter emitter = new SseJsonEmitter(5000L, jsonConverter);
        assertNotNull(emitter);
    }
}

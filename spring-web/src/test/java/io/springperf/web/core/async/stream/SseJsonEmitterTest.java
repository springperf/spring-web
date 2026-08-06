package io.springperf.web.core.async.stream;

import io.springperf.web.json.JsonConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

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

        String output = new String(baos.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(output.contains("data:{\"key\":\"value\"}"));
        verify(jsonConverter).toJson(any(OutputStream.class), eq(data));
    }

    @Test
    void constructor_withTimeout() {
        SseJsonEmitter emitter = new SseJsonEmitter(5000L, jsonConverter);
        assertNotNull(emitter);
    }
}

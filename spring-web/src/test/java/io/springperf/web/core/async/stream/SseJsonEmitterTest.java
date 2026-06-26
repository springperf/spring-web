package io.springperf.web.core.async.stream;

import io.springperf.web.json.JsonConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SseJsonEmitterTest {

    @Mock
    JsonConverter jsonConverter;

    @Test
    void encodeEventData_returnsJson() {
        when(jsonConverter.toJson("test")).thenReturn("\"test\"");

        SseJsonEmitter emitter = new SseJsonEmitter(jsonConverter);
        String result = emitter.encodeEventData("test");

        assertEquals("\"test\"", result);
        verify(jsonConverter).toJson("test");
    }

    @Test
    void encodeEventData_withObject_returnsJson() {
        Object data = new Object();
        when(jsonConverter.toJson(data)).thenReturn("{}");

        SseJsonEmitter emitter = new SseJsonEmitter(jsonConverter);
        String result = emitter.encodeEventData(data);

        assertEquals("{}", result);
        verify(jsonConverter).toJson(data);
    }

    @Test
    void constructor_withTimeout() {
        SseJsonEmitter emitter = new SseJsonEmitter(5000L, jsonConverter);
        assertNotNull(emitter);
    }
}

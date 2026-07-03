package io.springperf.web.core.async.stream;

import io.springperf.web.json.JsonConverter;
import io.springperf.web.util.MediaTypeUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StreamJsonEmitterTest {

    @Mock
    JsonConverter jsonConverter;

    @Test
    void extendResponse_setsContentType() {
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        when(response.getHeaders()).thenReturn(headers);

        StreamJsonEmitter emitter = new StreamJsonEmitter(jsonConverter);
        emitter.extendResponse(response);

        assertEquals(MediaTypeUtils.APPLICATION_STREAM_JSON, headers.getContentType());
    }

    @Test
    void extendResponse_doesNotOverrideExistingContentType() {
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        when(response.getHeaders()).thenReturn(headers);

        StreamJsonEmitter emitter = new StreamJsonEmitter(jsonConverter);
        emitter.extendResponse(response);

        assertEquals(MediaType.APPLICATION_JSON, headers.getContentType());
    }

    @Test
    void encodeToString_null_returnsNewline() {
        StreamJsonEmitter emitter = new StreamJsonEmitter(jsonConverter);
        assertEquals("\n", emitter.encodeToString(null).toString());
    }

    @Test
    void encodeToString_data_returnsJsonWithNewline() {
        when(jsonConverter.toJson("hello")).thenReturn("\"hello\"");

        StreamJsonEmitter emitter = new StreamJsonEmitter(jsonConverter);
        CharSequence result = emitter.encodeToString("hello");

        assertEquals("\"hello\"\n", result.toString());
        verify(jsonConverter).toJson("hello");
    }

    @Test
    void constructor_withTimeout() {
        StreamJsonEmitter emitter = new StreamJsonEmitter(5000L, jsonConverter);
        assertNotNull(emitter);
    }
}
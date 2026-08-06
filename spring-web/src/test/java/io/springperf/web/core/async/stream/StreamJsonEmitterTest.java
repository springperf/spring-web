package io.springperf.web.core.async.stream;

import io.springperf.web.json.JsonConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

        assertEquals(MediaType.APPLICATION_STREAM_JSON, headers.getContentType());
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
    void encode_null_returnsNewline() throws Exception {
        StreamJsonEmitter emitter = new StreamJsonEmitter(jsonConverter);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        emitter.encode(null, baos);
        assertEquals("\n", new String(baos.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void encode_data_returnsJsonWithNewline() throws Exception {
        byte[] jsonBytes = "\"hello\"".getBytes(StandardCharsets.UTF_8);
        doAnswer(invocation -> {
            ((OutputStream) invocation.getArgument(0)).write(jsonBytes);
            return null;
        }).when(jsonConverter).toJson(any(OutputStream.class), eq("hello"));

        StreamJsonEmitter emitter = new StreamJsonEmitter(jsonConverter);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        emitter.encode("hello", baos);

        assertEquals("\"hello\"\n", new String(baos.toByteArray(), StandardCharsets.UTF_8));
        verify(jsonConverter).toJson(any(OutputStream.class), eq("hello"));
    }

    @Test
    void constructor_withTimeout() {
        StreamJsonEmitter emitter = new StreamJsonEmitter(5000L, jsonConverter);
        assertNotNull(emitter);
    }
}
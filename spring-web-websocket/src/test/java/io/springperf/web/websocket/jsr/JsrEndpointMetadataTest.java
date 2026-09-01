package io.springperf.web.websocket.jsr;

import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JsrEndpointMetadata} 注解与参数解析单元测试。
 */
class JsrEndpointMetadataTest {

    @ServerEndpoint("/ws/chat/{roomId}")
    static class AnnotatedEndpoint {

        @OnOpen
        public void onOpen(Session session, EndpointConfig config, @PathParam("roomId") String roomId) {
        }

        @OnMessage
        public String onMessage(String message, @PathParam("roomId") String roomId) {
            return message;
        }

        @OnClose
        public void onClose(Session session, CloseReason reason) {
        }

        @OnError
        public void onError(Session session, Throwable error) {
        }
    }

    @Test
    void parsesEndpointAnnotation() {
        JsrEndpointMetadata metadata = new JsrEndpointMetadata(AnnotatedEndpoint.class);
        assertEquals("/ws/chat/{roomId}", metadata.getPath());
        assertEquals(0, metadata.getSubprotocols().length);
        assertEquals(AnnotatedEndpoint.class, metadata.getEndpointClass());
        assertNotNull(metadata.getOnOpen());
        assertNotNull(metadata.getOnMessage());
        assertNotNull(metadata.getOnClose());
        assertNotNull(metadata.getOnError());
        assertTrue(metadata.isInstantiable());
    }

    @Test
    void parsesOnOpenParams() {
        JsrEndpointMetadata metadata = new JsrEndpointMetadata(AnnotatedEndpoint.class);
        List<JsrEndpointMetadata.ParamSpec> params = metadata.getOnOpenParams();
        assertEquals(3, params.size());
        assertEquals(JsrEndpointMetadata.ParamKind.SESSION, params.get(0).kind);
        assertEquals(JsrEndpointMetadata.ParamKind.ENDPOINT_CONFIG, params.get(1).kind);
        assertEquals(JsrEndpointMetadata.ParamKind.PATH_PARAM, params.get(2).kind);
        assertEquals("roomId", params.get(2).name);
    }

    @Test
    void parsesOnMessageParams() {
        JsrEndpointMetadata metadata = new JsrEndpointMetadata(AnnotatedEndpoint.class);
        List<JsrEndpointMetadata.ParamSpec> params = metadata.getOnMessageParams();
        assertEquals(2, params.size());
        assertEquals(JsrEndpointMetadata.ParamKind.MESSAGE, params.get(0).kind);
        assertEquals(String.class, params.get(0).type);
        assertEquals(JsrEndpointMetadata.ParamKind.PATH_PARAM, params.get(1).kind);
        assertEquals(String.class, metadata.getMessageParam().type);
    }

    @Test
    void parsesOnCloseAndOnErrorParams() {
        JsrEndpointMetadata metadata = new JsrEndpointMetadata(AnnotatedEndpoint.class);
        assertEquals(JsrEndpointMetadata.ParamKind.CLOSE_REASON, metadata.getOnCloseParams().get(1).kind);
        assertEquals(JsrEndpointMetadata.ParamKind.THROWABLE, metadata.getOnErrorParams().get(1).kind);
    }

    @ServerEndpoint(value = "/ws/pojo", decoders = {UpperDecoder.class}, encoders = {UpperEncoder.class})
    static class PojoEndpoint {

        @OnMessage
        public String onMessage(MyPojo pojo) {
            return pojo.getValue();
        }
    }

    /** Decoder.Text 与 Encoder.Text 实现。 */
    public static class UpperDecoder implements jakarta.websocket.Decoder.Text<MyPojo> {
        @Override
        public MyPojo decode(String s) {
            return new MyPojo(s.toUpperCase());
        }

        @Override
        public boolean willDecode(String s) {
            return true;
        }

        @Override
        public void init(EndpointConfig config) {
        }

        @Override
        public void destroy() {
        }
    }

    public static class UpperEncoder implements jakarta.websocket.Encoder.Text<MyPojo> {
        @Override
        public String encode(MyPojo object) {
            return object.getValue();
        }

        @Override
        public void init(EndpointConfig config) {
        }

        @Override
        public void destroy() {
        }
    }

    public static class MyPojo {
        private final String value;

        public MyPojo(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    @Test
    void resolvesDecoderAndEncoderByGenericType() {
        JsrEndpointMetadata metadata = new JsrEndpointMetadata(PojoEndpoint.class);
        assertEquals(java.util.Arrays.asList(UpperDecoder.class), metadata.getDecoders());
        assertEquals(java.util.Arrays.asList(UpperEncoder.class), metadata.getEncoders());

        Class<? extends jakarta.websocket.Decoder> decoder = metadata.findDecoder(MyPojo.class, true);
        assertEquals(UpperDecoder.class, decoder);
        // 二进制无匹配
        assertNull(metadata.findDecoder(MyPojo.class, false));

        Class<? extends jakarta.websocket.Encoder> encoder = metadata.findEncoder(MyPojo.class, true);
        assertEquals(UpperEncoder.class, encoder);
        assertNull(metadata.findEncoder(MyPojo.class, false));
    }

    @Test
    void messageTypeClassification() {
        assertTrue(JsrEndpointMetadata.isPlainMessageType(String.class));
        assertTrue(JsrEndpointMetadata.isPlainMessageType(byte[].class));
        assertTrue(JsrEndpointMetadata.isPlainMessageType(ByteBuffer.class));
        assertFalse(JsrEndpointMetadata.isPlainMessageType(MyPojo.class));
    }
}

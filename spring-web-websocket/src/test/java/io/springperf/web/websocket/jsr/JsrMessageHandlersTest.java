package io.springperf.web.websocket.jsr;

import jakarta.websocket.Decoder;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.PongMessage;
import jakarta.websocket.server.ServerEndpoint;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态 {@link MessageHandler} 注册表的交付语义：载荷按 handler 声明的 {@code T} 转换、Partial 以整条消息交付。
 */
class JsrMessageHandlersTest {

    private final JsrCodecRegistry codecRegistry = new JsrCodecRegistry(
            new JsrEndpointConfigAdapter(new JsrEndpointMetadata(DecoderEndpoint.class)));

    @ServerEndpoint(value = "/decoder", decoders = MessageDecoder.class)
    static class DecoderEndpoint {
    }

    /** 供 POJO 交付路径使用的消息载体。 */
    static final class Message {

        private final String value;

        Message(String value) {
            this.value = value;
        }

        String getValue() {
            return value;
        }
    }

    public static class MessageDecoder implements Decoder.Text<Message> {

        @Override
        public Message decode(String s) {
            return new Message(s);
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

    @Test
    void deliverText_wholeString() throws Exception {
        JsrMessageHandlers handlers = new JsrMessageHandlers();
        List<String> received = new ArrayList<>();
        handlers.add(JsrMessageHandlers.TEXT, String.class, (MessageHandler.Whole<String>) received::add, false);

        assertTrue(handlers.deliverText("hello", codecRegistry));
        assertEquals(List.of("hello"), received);
    }

    @Test
    void deliverText_readerGetsIndependentInstance() throws Exception {
        JsrMessageHandlers handlers = new JsrMessageHandlers();
        List<String> received = new ArrayList<>();
        handlers.add(JsrMessageHandlers.TEXT, Reader.class, (MessageHandler.Whole<Reader>) reader -> {
            try {
                StringBuilder text = new StringBuilder();
                int c;
                while ((c = reader.read()) != -1) {
                    text.append((char) c);
                }
                received.add(text.toString());
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
        }, false);

        assertTrue(handlers.deliverText("pojo-text", codecRegistry));
        assertEquals(List.of("pojo-text"), received);
    }

    @Test
    void deliverText_decodesToConfiguredType() throws Exception {
        JsrMessageHandlers handlers = new JsrMessageHandlers();
        List<String> received = new ArrayList<>();
        handlers.add(JsrMessageHandlers.TEXT, Message.class,
                (MessageHandler.Whole<Message>) message -> received.add(message.getValue()), false);

        handlers.deliverText("payload", codecRegistry);

        assertEquals(List.of("payload"), received);
    }

    @Test
    void deliverBinary_byteArrayAndByteBuffer() throws Exception {
        JsrMessageHandlers handlers = new JsrMessageHandlers();
        List<String> received = new ArrayList<>();
        handlers.add(JsrMessageHandlers.BINARY, byte[].class,
                (MessageHandler.Whole<byte[]>) bytes -> received.add("bytes:" + bytes.length), false);

        assertTrue(handlers.deliverBinary(ByteBuffer.wrap(new byte[] { 1, 2, 3 }), codecRegistry));
        assertEquals(List.of("bytes:3"), received);
    }

    @Test
    void deliverBinary_inputStream() throws Exception {
        JsrMessageHandlers handlers = new JsrMessageHandlers();
        List<String> received = new ArrayList<>();
        handlers.add(JsrMessageHandlers.BINARY, InputStream.class, (MessageHandler.Whole<InputStream>) stream -> {
            try {
                received.add(String.valueOf(stream.read()));
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
        }, false);

        handlers.deliverBinary(ByteBuffer.wrap(new byte[] { 42 }), codecRegistry);

        assertEquals(List.of("42"), received);
    }

    /** 本框架的消息模型是完整消息，因此 Partial 以「唯一分片 + last=true」交付。 */
    @Test
    void deliverText_partialReceivesWholeMessageWithLastTrue() throws Exception {
        JsrMessageHandlers handlers = new JsrMessageHandlers();
        List<String> received = new ArrayList<>();
        handlers.add(JsrMessageHandlers.TEXT, String.class,
                (MessageHandler.Partial<String>) (part, last) -> received.add(part + "/" + last), true);

        assertTrue(handlers.deliverText("chunk", codecRegistry));

        assertEquals(List.of("chunk/true"), received);
    }

    @Test
    void deliverPong_passesPongMessage() {
        JsrMessageHandlers handlers = new JsrMessageHandlers();
        List<String> received = new ArrayList<>();
        handlers.add(JsrMessageHandlers.PONG, PongMessage.class, (MessageHandler.Whole<PongMessage>) pong -> received
                .add("pong:" + pong.getApplicationData().remaining()), false);

        ByteBuffer data = ByteBuffer.wrap(new byte[] { 9, 9 });
        assertTrue(handlers.deliverPong((PongMessage) () -> data));
        assertEquals(List.of("pong:2"), received);
    }

    @Test
    void deliver_unregisteredNativeType_returnsFalse() throws Exception {
        JsrMessageHandlers handlers = new JsrMessageHandlers();
        handlers.add(JsrMessageHandlers.TEXT, String.class, (MessageHandler.Whole<String>) text -> {
        }, false);

        assertFalse(handlers.deliverBinary(ByteBuffer.wrap(new byte[] { 1 }), codecRegistry));
        assertFalse(handlers.deliverPong((PongMessage) () -> ByteBuffer.allocate(0)));
    }

    @Test
    void declaredMessageType_resolvesThroughInheritedInterface() {
        assertEquals(String.class, JsrMessageHandlers.declaredMessageType(new StringHandlerImpl()));
    }

    @Test
    void declaredMessageType_lambdaFailsWithoutReifiedType() {
        MessageHandler handler = (MessageHandler.Whole<String>) text -> {
        };
        assertThrows(IllegalStateException.class, () -> JsrMessageHandlers.declaredMessageType(handler));
    }

    /** 接口继承 {@code Whole<String>}：泛型参数在父接口上，必须递归才能拿到。 */
    interface StringHandler extends MessageHandler.Whole<String> {
    }

    static final class StringHandlerImpl implements StringHandler {

        @Override
        public void onMessage(String message) {
        }
    }

    /* ==================== 注册表约束：重复注册 / 未知类型 / 注销 ==================== */

    @Test
    void add_duplicateNativeType_throws() {
        JsrMessageHandlers handlers = new JsrMessageHandlers();
        MessageHandler.Whole<byte[]> binary = bytes -> {
        };
        handlers.add(JsrMessageHandlers.BINARY, byte[].class, binary, false);
        assertThrows(IllegalStateException.class,
                () -> handlers.add(JsrMessageHandlers.BINARY, byte[].class, binary, false), "每种原生类型最多一个动态 handler");

        MessageHandler.Whole<PongMessage> pong = message -> {
        };
        handlers.add(JsrMessageHandlers.PONG, PongMessage.class, pong, false);
        assertThrows(IllegalStateException.class,
                () -> handlers.add(JsrMessageHandlers.PONG, PongMessage.class, pong, false));
    }

    @Test
    void add_typeAlreadyTakenByAnnotatedOnMessage_throws() {
        JsrMessageHandlers handlers = new JsrMessageHandlers();
        handlers.markAnnotated(JsrMessageHandlers.BINARY);
        assertThrows(IllegalStateException.class,
                () -> handlers.add(JsrMessageHandlers.BINARY, byte[].class, (MessageHandler.Whole<byte[]>) b -> {
                }, false));

        handlers.markAnnotated(JsrMessageHandlers.PONG);
        assertThrows(IllegalStateException.class, () -> handlers.add(JsrMessageHandlers.PONG, PongMessage.class,
                (MessageHandler.Whole<PongMessage>) p -> {
                }, false));
    }

    @Test
    void add_unsupportedKind_throwsIllegalArgument() {
        JsrMessageHandlers handlers = new JsrMessageHandlers();
        assertThrows(IllegalArgumentException.class,
                () -> handlers.add(99, String.class, (MessageHandler.Whole<String>) s -> {
                }, false));
    }

    @Test
    void remove_clearsBinaryAndPongSlots() throws Exception {
        JsrMessageHandlers handlers = new JsrMessageHandlers();
        MessageHandler.Whole<byte[]> binary = bytes -> {
        };
        MessageHandler.Whole<PongMessage> pong = message -> {
        };
        handlers.add(JsrMessageHandlers.BINARY, byte[].class, binary, false);
        handlers.add(JsrMessageHandlers.PONG, PongMessage.class, pong, false);

        handlers.remove(binary);
        assertFalse(handlers.deliverBinary(ByteBuffer.wrap(new byte[] { 1 }), codecRegistry));

        handlers.remove(pong);
        assertFalse(handlers.deliverPong((PongMessage) () -> ByteBuffer.allocate(0)));
        assertTrue(handlers.isEmpty());
    }

    @Test
    void emptyRegistry_snapshotEmptyAndNothingDelivered() throws Exception {
        JsrMessageHandlers handlers = new JsrMessageHandlers();
        assertTrue(handlers.snapshot().isEmpty());
        assertTrue(handlers.isEmpty());
        assertFalse(handlers.deliverText("x", codecRegistry));
    }

    /* ==================== 二进制载荷的另外两种目标类型 ==================== */

    @Test
    void deliverBinary_byteBufferIsPassedThroughAsIs() throws Exception {
        JsrMessageHandlers handlers = new JsrMessageHandlers();
        List<ByteBuffer> received = new ArrayList<>();
        handlers.add(JsrMessageHandlers.BINARY, ByteBuffer.class, (MessageHandler.Whole<ByteBuffer>) received::add,
                false);

        ByteBuffer payload = ByteBuffer.wrap(new byte[] { 5, 6 });
        handlers.deliverBinary(payload, codecRegistry);

        assertSame(payload, received.get(0), "ByteBuffer 目标类型应直传，不做额外拷贝");
    }

    @Test
    void deliverBinary_decodesWithBinaryDecoder() throws Exception {
        JsrCodecRegistry binaryRegistry = new JsrCodecRegistry(
                new JsrEndpointConfigAdapter(new JsrEndpointMetadata(BinaryDecoderEndpoint.class)));
        JsrMessageHandlers handlers = new JsrMessageHandlers();
        List<String> received = new ArrayList<>();
        handlers.add(JsrMessageHandlers.BINARY, Message.class,
                (MessageHandler.Whole<Message>) message -> received.add(message.getValue()), false);

        handlers.deliverBinary(ByteBuffer.wrap(new byte[] { 7 }), binaryRegistry);

        assertEquals(List.of("bin"), received, "自定义 binary Decoder 必须参与转换");
    }

    @ServerEndpoint(value = "/binary-decoder", decoders = BinaryMessageDecoder.class)
    static class BinaryDecoderEndpoint {
    }

    public static class BinaryMessageDecoder implements Decoder.Binary<Message> {

        @Override
        public Message decode(ByteBuffer bytes) {
            return new Message("bin");
        }

        @Override
        public boolean willDecode(ByteBuffer bytes) {
            return true;
        }
    }

    /* ==================== 泛型推断的拒绝路径 ==================== */

    @Test
    void declaredMessageType_partialHandlerResolvesType() {
        MessageHandler.Partial<String> partial = new MessageHandler.Partial<String>() {
            @Override
            public void onMessage(String part, boolean last) {
            }
        };
        assertEquals(String.class, JsrMessageHandlers.declaredMessageType(partial),
                "Partial 与 Whole 一样要能从匿名类泛型还原出消息类型");
    }

    @Test
    void declaredMessageType_plainMarkerHandler_throws() {
        MessageHandler plain = new MessageHandler() {
        };
        assertThrows(IllegalStateException.class, () -> JsrMessageHandlers.declaredMessageType(plain));
    }

    /** 泛型落在父类上（{@code class Concrete extends Base<String>}）：擦除后无法还原，按规范要求改用具 Class 的重载。 */
    @Test
    void declaredMessageType_genericSuperclass_throws() {
        assertThrows(IllegalStateException.class,
                () -> JsrMessageHandlers.declaredMessageType(new ConcreteInheritedHandler()));
    }

    abstract static class GenericBaseHandler<T> implements MessageHandler.Whole<T> {
    }

    static final class ConcreteInheritedHandler extends GenericBaseHandler<String> {

        @Override
        public void onMessage(String message) {
        }
    }
}

package io.springperf.web.websocket.jsr;

import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;
import javax.websocket.EncodeException;
import org.springframework.core.ResolvableType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 端点级 {@link Decoder}/{@link Encoder} 实例管理。
 *
 * <p>JSR-356 规范要求 Decoder/Encoder 与端点实例一一对应并在会话生命周期内管理。
 * 此注册表在连接建立时实例化、初始化，会话关闭时销毁。</p>
 *
 * @author huangcanda
 * @since 2.7.6
 */
public class JsrCodecRegistry {

    private final List<Decoder> decoderInstances = new ArrayList<>();
    private final List<Encoder> encoderInstances = new ArrayList<>();

    /** text/binary 解码器按泛型类型索引。 */
    private final Map<Class<?>, Decoder.Text<?>> textDecoders = new HashMap<>();
    private final Map<Class<?>, Decoder.Binary<?>> binaryDecoders = new HashMap<>();
    private final Map<Class<?>, Encoder.Text<?>> textEncoders = new HashMap<>();
    private final Map<Class<?>, Encoder.Binary<?>> binaryEncoders = new HashMap<>();

    @SuppressWarnings({"rawtypes", "unchecked"})
    public JsrCodecRegistry(JsrEndpointConfigAdapter config) {
        EndpointConfig endpointConfig = config;
        for (Class<? extends Decoder> decoderClass : config.getDecoders()) {
            try {
                Decoder decoder = decoderClass.getDeclaredConstructor().newInstance();
                decoder.init(endpointConfig);
                decoderInstances.add(decoder);
                ResolvableType rt = ResolvableType.forClass(decoderClass).as(Decoder.Text.class);
                if (rt.hasGenerics()) {
                    textDecoders.put(rt.getGeneric(0).resolve(), (Decoder.Text<?>) decoder);
                }
                rt = ResolvableType.forClass(decoderClass).as(Decoder.Binary.class);
                if (rt.hasGenerics()) {
                    binaryDecoders.put(rt.getGeneric(0).resolve(), (Decoder.Binary<?>) decoder);
                }
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to instantiate Decoder " + decoderClass.getName(), ex);
            }
        }
        for (Class<? extends Encoder> encoderClass : config.getEncoders()) {
            try {
                Encoder encoder = encoderClass.getDeclaredConstructor().newInstance();
                encoder.init(endpointConfig);
                encoderInstances.add(encoder);
                ResolvableType rt = ResolvableType.forClass(encoderClass).as(Encoder.Text.class);
                if (rt.hasGenerics()) {
                    textEncoders.put(rt.getGeneric(0).resolve(), (Encoder.Text<?>) encoder);
                }
                rt = ResolvableType.forClass(encoderClass).as(Encoder.Binary.class);
                if (rt.hasGenerics()) {
                    binaryEncoders.put(rt.getGeneric(0).resolve(), (Encoder.Binary<?>) encoder);
                }
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to instantiate Encoder " + encoderClass.getName(), ex);
            }
        }
    }

    /**
     * 解码文本帧。
     *
     * @param text       帧文本
     * @param targetType @OnMessage 消息参数类型（String 直接返回，否则查 Decoder.Text）
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Object decodeText(String text, Class<?> targetType) throws javax.websocket.DecodeException {
        if (String.class == targetType) {
            return text;
        }
        Decoder.Text decoder = findTextDecoder(textDecoders, targetType);
        if (decoder == null) {
            throw new javax.websocket.DecodeException(text, "No Decoder.Text for type " + targetType.getName());
        }
        return decoder.decode(text);
    }

    /**
     * 解码二进制帧。
     *
     * @param buf       帧二进制内容
     * @param targetType @OnMessage 消息参数类型（byte[]/ByteBuffer 直接返回，否则查 Decoder.Binary）
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Object decodeBinary(ByteBuffer buf, Class<?> targetType) throws javax.websocket.DecodeException {
        if (byte[].class == targetType) {
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            return bytes;
        }
        if (ByteBuffer.class == targetType) {
            return buf;
        }
        Decoder.Binary decoder = findBinaryDecoder(binaryDecoders, targetType);
        if (decoder == null) {
            throw new javax.websocket.DecodeException(buf, "No Decoder.Binary for type " + targetType.getName());
        }
        return decoder.decode(buf);
    }

    /**
     * 将对象编码并发送。
     *
     * @param session 目标会话
     * @param data    要发送的对象
     * @param async   是否异步发送（true 走 Async remote，此处仅编码，发送由调用方处理）
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public EncodedPayload encode(Object data, boolean preferText) throws EncodeException {
        Class<?> type = data.getClass();
        Encoder.Text textEncoder = findTextEncoder(textEncoders, type);
        if (textEncoder != null && preferText) {
            return EncodedPayload.text((String) textEncoder.encode(data));
        }
        Encoder.Binary binaryEncoder = findBinaryEncoder(binaryEncoders, type);
        if (binaryEncoder != null) {
            return EncodedPayload.binary((ByteBuffer) binaryEncoder.encode(data));
        }
        if (textEncoder != null) {
            return EncodedPayload.text((String) textEncoder.encode(data));
        }
        throw new EncodeException(data, "No matching Encoder for type " + type.getName());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Decoder.Text findTextDecoder(Map map, Class<?> targetType) {
        Object found = map.get(targetType);
        if (found != null) {
            return (Decoder.Text) found;
        }
        // JSR-356 语义：选中"解码类型 T 可赋值给 @OnMessage 参数类型 P"的 decoder，
        // 即 targetType.isAssignableFrom(decodedType)（多态场景，如 @OnMessage(IFoo) + Decoder.Text<Foo>）。
        // 反向判断（decodedType.isAssignableFrom(targetType)）会拒选合法 decoder / 误选泛型超类型。
        for (Object entryObj : map.entrySet()) {
            Map.Entry entry = (Map.Entry) entryObj;
            if (targetType.isAssignableFrom((Class<?>) entry.getKey())) {
                return (Decoder.Text) entry.getValue();
            }
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Decoder.Binary findBinaryDecoder(Map map, Class<?> targetType) {
        Object found = map.get(targetType);
        if (found != null) {
            return (Decoder.Binary) found;
        }
        for (Object entryObj : map.entrySet()) {
            Map.Entry entry = (Map.Entry) entryObj;
            if (targetType.isAssignableFrom((Class<?>) entry.getKey())) {
                return (Decoder.Binary) entry.getValue();
            }
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Encoder.Text findTextEncoder(Map map, Class<?> targetType) {
        Object found = map.get(targetType);
        if (found != null) {
            return (Encoder.Text) found;
        }
        for (Object entryObj : map.entrySet()) {
            Map.Entry entry = (Map.Entry) entryObj;
            if (((Class<?>) entry.getKey()).isAssignableFrom(targetType)) {
                return (Encoder.Text) entry.getValue();
            }
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Encoder.Binary findBinaryEncoder(Map map, Class<?> targetType) {
        Object found = map.get(targetType);
        if (found != null) {
            return (Encoder.Binary) found;
        }
        for (Object entryObj : map.entrySet()) {
            Map.Entry entry = (Map.Entry) entryObj;
            if (((Class<?>) entry.getKey()).isAssignableFrom(targetType)) {
                return (Encoder.Binary) entry.getValue();
            }
        }
        return null;
    }

    /** 销毁所有 Decoder/Encoder 实例。 */
    public void destroy() {
        for (Decoder decoder : decoderInstances) {
            try {
                decoder.destroy();
            } catch (Exception ignored) {
                // 忽略销毁异常
            }
        }
        for (Encoder encoder : encoderInstances) {
            try {
                encoder.destroy();
            } catch (Exception ignored) {
                // 忽略销毁异常
            }
        }
    }

    /** 编码结果。 */
    public static final class EncodedPayload {
        private final String text;
        private final ByteBuffer binary;

        private EncodedPayload(String text, ByteBuffer binary) {
            this.text = text;
            this.binary = binary;
        }

        static EncodedPayload text(String text) {
            return new EncodedPayload(text, null);
        }

        static EncodedPayload binary(ByteBuffer binary) {
            return new EncodedPayload(null, binary);
        }

        public boolean isText() {
            return text != null;
        }

        public String getText() {
            return text;
        }

        public ByteBuffer getBinary() {
            return binary;
        }
    }
}

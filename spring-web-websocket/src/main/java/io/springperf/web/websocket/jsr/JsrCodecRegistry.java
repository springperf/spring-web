package io.springperf.web.websocket.jsr;

import jakarta.websocket.Decoder;
import jakarta.websocket.Encoder;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.EncodeException;
import org.springframework.core.ResolvableType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 端点级 {@link Decoder}/{@link Encoder} 实例管理。
 * <p>
 * JSR-356 规范要求 Decoder/Encoder 与端点实例一一对应并在会话生命周期内管理。 此注册表在连接建立时实例化、初始化，会话关闭时销毁。
 * </p>
 *
 * @author huangcanda
 *
 * @since 3.5.6
 */
public class JsrCodecRegistry {

    /** 「该类型没有可用 codec」的负缓存标记：连「找不到」也要缓存，否则每次落空都全表扫描。 */
    private static final Object NO_CODEC = new Object();

    private final List<Decoder> decoderInstances = new ArrayList<>();
    private final List<Encoder> encoderInstances = new ArrayList<>();

    /** text/binary 解码器按泛型类型索引。 */
    private final Map<Class<?>, Decoder.Text<?>> textDecoders = new HashMap<>();
    private final Map<Class<?>, Decoder.Binary<?>> binaryDecoders = new HashMap<>();
    private final Map<Class<?>, Encoder.Text<?>> textEncoders = new HashMap<>();
    private final Map<Class<?>, Encoder.Binary<?>> binaryEncoders = new HashMap<>();

    /**
     * 解析结果缓存（连接级，注册表本身即 per-connection）：{@code @OnMessage} 参数类型与发送对象类型在一条连接内是固定的小集合， 缓存后每帧/每次发送不再做 isAssignableFrom
     * 全表扫描。
     * <p>
     * 用 ConcurrentHashMap：发送侧可能由多个业务线程并发调用（与批处理开关同为跨线程可见）。
     * </p>
     */
    private final Map<Class<?>, Object> textDecoderCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> binaryDecoderCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> textEncoderCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Object> binaryEncoderCache = new ConcurrentHashMap<>();

    @SuppressWarnings({ "rawtypes", "unchecked" })
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
     * @param text
     *            帧文本
     * @param targetType
     *            {@code @OnMessage} 消息参数类型（String 直接返回，否则查 Decoder.Text）
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public Object decodeText(String text, Class<?> targetType) throws jakarta.websocket.DecodeException {
        if (String.class == targetType) {
            return text;
        }
        Decoder.Text decoder = textDecoderFor(targetType);
        if (decoder == null) {
            throw new jakarta.websocket.DecodeException(text, "No Decoder.Text for type " + targetType.getName());
        }
        return decoder.decode(text);
    }

    /**
     * 解码二进制帧。
     *
     * @param buf
     *            帧二进制内容
     * @param targetType
     *            {@code @OnMessage} 消息参数类型（byte[]/ByteBuffer 直接返回，否则查 Decoder.Binary）
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public Object decodeBinary(ByteBuffer buf, Class<?> targetType) throws jakarta.websocket.DecodeException {
        if (byte[].class == targetType) {
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            return bytes;
        }
        if (ByteBuffer.class == targetType) {
            return buf;
        }
        Decoder.Binary decoder = binaryDecoderFor(targetType);
        if (decoder == null) {
            throw new jakarta.websocket.DecodeException(buf, "No Decoder.Binary for type " + targetType.getName());
        }
        return decoder.decode(buf);
    }

    /**
     * 该类型能否由文本帧解码得到（{@link String} 内建，其余查 {@link Decoder.Text}）。
     * <p>
     * 供 {@code Session#addMessageHandler(Class, MessageHandler)} 在注册阶段判定 handler 属于哪种原生消息类型。
     * </p>
     */
    boolean canDecodeText(Class<?> targetType) {
        return String.class == targetType || textDecoderFor(targetType) != null;
    }

    /**
     * 该类型能否由二进制帧解码得到（{@code byte[]}/{@link ByteBuffer} 内建，其余查 {@link Decoder.Binary}）。
     */
    boolean canDecodeBinary(Class<?> targetType) {
        return byte[].class == targetType || ByteBuffer.class == targetType || binaryDecoderFor(targetType) != null;
    }

    /**
     * 将对象编码为 {@link EncodedPayload}（实际发送由调用方处理）。
     *
     * @param data
     *            要编码的对象
     * @param preferText
     *            文本与二进制编码器都可用时是否优先文本编码
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public EncodedPayload encode(Object data, boolean preferText) throws EncodeException {
        Class<?> type = data.getClass();
        Encoder.Text textEncoder = textEncoderFor(type);
        if (textEncoder != null && preferText) {
            return EncodedPayload.text((String) textEncoder.encode(data));
        }
        Encoder.Binary binaryEncoder = binaryEncoderFor(type);
        if (binaryEncoder != null) {
            return EncodedPayload.binary((ByteBuffer) binaryEncoder.encode(data));
        }
        if (textEncoder != null) {
            return EncodedPayload.text((String) textEncoder.encode(data));
        }
        throw new EncodeException(data, "No matching Encoder for type " + type.getName());
    }

    // ===================== 解析结果缓存（命中即返回，未命中扫描一次并连负结果一起缓存） =====================

    /**
     * 包内测试钩子：四张解析缓存的条目总数。
     * <p>
     * 用于验证「同一类型只解析一次、负结果也缓存」这一性能契约（生产代码不调用）。
     * </p>
     */
    int cachedCodecCount() {
        return textDecoderCache.size() + binaryDecoderCache.size() + textEncoderCache.size()
                + binaryEncoderCache.size();
    }

    @SuppressWarnings("rawtypes")
    private Decoder.Text textDecoderFor(Class<?> targetType) {
        Object cached = textDecoderCache.get(targetType);
        if (cached == null) {
            Decoder.Text found = findTextDecoder(textDecoders, targetType);
            cached = found != null ? found : NO_CODEC;
            textDecoderCache.put(targetType, cached);
        }
        return cached == NO_CODEC ? null : (Decoder.Text) cached;
    }

    @SuppressWarnings("rawtypes")
    private Decoder.Binary binaryDecoderFor(Class<?> targetType) {
        Object cached = binaryDecoderCache.get(targetType);
        if (cached == null) {
            Decoder.Binary found = findBinaryDecoder(binaryDecoders, targetType);
            cached = found != null ? found : NO_CODEC;
            binaryDecoderCache.put(targetType, cached);
        }
        return cached == NO_CODEC ? null : (Decoder.Binary) cached;
    }

    @SuppressWarnings("rawtypes")
    private Encoder.Text textEncoderFor(Class<?> targetType) {
        Object cached = textEncoderCache.get(targetType);
        if (cached == null) {
            Encoder.Text found = findTextEncoder(textEncoders, targetType);
            cached = found != null ? found : NO_CODEC;
            textEncoderCache.put(targetType, cached);
        }
        return cached == NO_CODEC ? null : (Encoder.Text) cached;
    }

    @SuppressWarnings("rawtypes")
    private Encoder.Binary binaryEncoderFor(Class<?> targetType) {
        Object cached = binaryEncoderCache.get(targetType);
        if (cached == null) {
            Encoder.Binary found = findBinaryEncoder(binaryEncoders, targetType);
            cached = found != null ? found : NO_CODEC;
            binaryEncoderCache.put(targetType, cached);
        }
        return cached == NO_CODEC ? null : (Encoder.Binary) cached;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
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

    @SuppressWarnings({ "rawtypes", "unchecked" })
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

    @SuppressWarnings({ "rawtypes", "unchecked" })
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

    @SuppressWarnings({ "rawtypes", "unchecked" })
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

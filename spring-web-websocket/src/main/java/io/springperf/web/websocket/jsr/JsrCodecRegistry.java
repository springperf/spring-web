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
 * 绔偣绾?{@link Decoder}/{@link Encoder} 瀹炰緥绠＄悊銆?
 *
 * <p>JSR-356 瑙勮寖瑕佹眰 Decoder/Encoder 涓庣鐐瑰疄渚嬩竴涓€瀵瑰簲骞跺湪浼氳瘽鐢熷懡鍛ㄦ湡鍐呯鐞嗐€?
 * 姝ゆ敞鍐岃〃鍦ㄨ繛鎺ュ缓绔嬫椂瀹炰緥鍖栥€佸垵濮嬪寲锛屼細璇濆叧闂椂閿€姣併€?/p>
 *
 * @author huangcanda
 * @since 3.5.6
 */
public class JsrCodecRegistry {

    private final List<Decoder> decoderInstances = new ArrayList<>();
    private final List<Encoder> encoderInstances = new ArrayList<>();

    /** text/binary 瑙ｇ爜鍣ㄦ寜娉涘瀷绫诲瀷绱㈠紩銆?*/
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
     * 瑙ｇ爜鏂囨湰甯с€?
     *
     * @param text       甯ф枃鏈?
     * @param targetType @OnMessage 娑堟伅鍙傛暟绫诲瀷锛圫tring 鐩存帴杩斿洖锛屽惁鍒欐煡 Decoder.Text锛?
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
     * 瑙ｇ爜浜岃繘鍒跺抚銆?
     *
     * @param buf       甯т簩杩涘埗鍐呭
     * @param targetType @OnMessage 娑堟伅鍙傛暟绫诲瀷锛坆yte[]/ByteBuffer 鐩存帴杩斿洖锛屽惁鍒欐煡 Decoder.Binary锛?
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
     * 灏嗗璞＄紪鐮佸苟鍙戦€併€?
     *
     * @param session 鐩爣浼氳瘽
     * @param data    瑕佸彂閫佺殑瀵硅薄
     * @param async   鏄惁寮傛鍙戦€侊紙true 璧?Async remote锛屾澶勪粎缂栫爜锛屽彂閫佺敱璋冪敤鏂瑰鐞嗭級
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

    /** 閿€姣佹墍鏈?Decoder/Encoder 瀹炰緥銆?*/
    public void destroy() {
        for (Decoder decoder : decoderInstances) {
            try {
                decoder.destroy();
            } catch (Exception ignored) {
                // 蹇界暐閿€姣佸紓甯?
            }
        }
        for (Encoder encoder : encoderInstances) {
            try {
                encoder.destroy();
            } catch (Exception ignored) {
                // 蹇界暐閿€姣佸紓甯?
            }
        }
    }

    /** 缂栫爜缁撴灉銆?*/
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

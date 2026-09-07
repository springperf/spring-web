package io.springperf.web.websocket.jsr;

import javax.websocket.CloseReason;
import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.PongMessage;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JSR-356 {@link ServerEndpoint} 娉ㄨВ绔偣鐨勫厓鏁版嵁銆?
 *
 * <p>鍚姩鏃朵竴娆℃€цВ鏋愮鐐圭被锛屽皢 {@code @ServerEndpoint} 娉ㄨВ灞炴€т笌
 * {@code @OnOpen/@OnMessage/@OnClose/@OnError} 鐢熷懡鍛ㄦ湡鏂规硶鍙婂弬鏁版敞鍏ヨ鍒欑紦瀛橈紝
 * 璇锋眰璺緞涓婇浂鍙嶅皠銆侀浂娉ㄨВ鏌ユ壘锛堥伒寰鏋舵€ц兘鍘熷垯锛夈€?/p>
 *
 * @author huangcanda
 * @since 3.2.5
 */
public class JsrEndpointMetadata {

    /** 鐢熷懡鍛ㄦ湡鏂规硶鍙傛暟娉ㄥ叆绫诲瀷銆?*/
    public enum ParamKind {
        /** javax.websocket.Session */
        SESSION,
        /** javax.websocket.EndpointConfig锛堜粎 @OnOpen锛?*/
        ENDPOINT_CONFIG,
        /** javax.websocket.CloseReason锛堜粎 @OnClose锛?*/
        CLOSE_REASON,
        /** Throwable锛堜粎 @OnError锛?*/
        THROWABLE,
        /** 娑堟伅浣擄紙浠?@OnMessage锛夛細String/ByteBuffer/byte[]/PongMessage/POJO */
        MESSAGE,
        /** @PathParam */
        PATH_PARAM
    }

    /** 鍗曚釜鐢熷懡鍛ㄦ湡鏂规硶鍙傛暟鐨勬敞鍏ヨ鏍笺€?*/
    public static final class ParamSpec {
        public final ParamKind kind;
        /** PATH_PARAM 鏃舵敞瑙ｅ€硷紱MESSAGE 鏃朵负娑堟伅浣撶被鍨?*/
        public final String name;
        /** MESSAGE 鏃剁殑鍙傛暟绫诲瀷锛圫tring/byte[]/ByteBuffer/PongMessage/POJO锛?*/
        public final Class<?> type;

        ParamSpec(ParamKind kind, String name, Class<?> type) {
            this.kind = kind;
            this.name = name;
            this.type = type;
        }
    }

    private final Class<?> endpointClass;
    private final String path;
    private final String[] subprotocols;
    private final List<Class<? extends Decoder>> decoders;
    private final List<Class<? extends Encoder>> encoders;
    private final Class<? extends ServerEndpointConfig.Configurator> configuratorClass;

    private final Method onOpen;
    private final Method onMessage;
    private final Method onClose;
    private final Method onError;

    private final List<ParamSpec> onOpenParams;
    private final List<ParamSpec> onMessageParams;
    private final List<ParamSpec> onCloseParams;
    private final List<ParamSpec> onErrorParams;

    /** @OnMessage 娑堟伅浣撳弬鏁拌鏍硷紙鏃犳秷鎭弬鏁版椂涓?null锛夈€?*/
    private final ParamSpec messageParam;

    @SuppressWarnings("unchecked")
    public JsrEndpointMetadata(Class<?> endpointClass) {
        this.endpointClass = endpointClass;
        ServerEndpoint endpoint = AnnotatedElementUtils.findMergedAnnotation(endpointClass, ServerEndpoint.class);
        if (endpoint == null) {
            throw new IllegalArgumentException(
                    "Class " + endpointClass.getName() + " is not annotated with @ServerEndpoint");
        }
        this.path = endpoint.value();
        this.subprotocols = endpoint.subprotocols();
        this.decoders = toDecoderList(endpoint.decoders());
        this.encoders = toEncoderList(endpoint.encoders());
        this.configuratorClass = endpoint.configurator();

        this.onOpen = findAnnotatedMethod(endpointClass, OnOpen.class);
        this.onMessage = findAnnotatedMethod(endpointClass, OnMessage.class);
        this.onClose = findAnnotatedMethod(endpointClass, OnClose.class);
        this.onError = findAnnotatedMethod(endpointClass, OnError.class);

        this.onOpenParams = onOpen != null ? parseParams(onOpen, true) : Collections.emptyList();
        this.onMessageParams = onMessage != null ? parseParams(onMessage, false) : Collections.emptyList();
        this.onCloseParams = onClose != null ? parseParams(onClose, false) : Collections.emptyList();
        this.onErrorParams = onError != null ? parseParams(onError, false) : Collections.emptyList();

        this.messageParam = findMessageParam(onMessageParams);
    }

    private static List<Class<? extends Decoder>> toDecoderList(Class<? extends Decoder>[] arr) {
        List<Class<? extends Decoder>> list = new ArrayList<>(arr.length);
        Collections.addAll(list, arr);
        return list;
    }

    private static List<Class<? extends Encoder>> toEncoderList(Class<? extends Encoder>[] arr) {
        List<Class<? extends Encoder>> list = new ArrayList<>(arr.length);
        Collections.addAll(list, arr);
        return list;
    }

    private static Method findAnnotatedMethod(Class<?> clazz, Class<? extends Annotation> annotationType) {
        for (Class<?> current = clazz; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (AnnotatedElementUtils.findMergedAnnotation(method, annotationType) != null) {
                    return method;
                }
            }
        }
        return null;
    }

    private static List<ParamSpec> parseParams(Method method, boolean isOnOpen) {
        Class<?>[] types = method.getParameterTypes();
        Annotation[][] annotations = method.getParameterAnnotations();
        List<ParamSpec> specs = new ArrayList<>(types.length);
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (Session.class.isAssignableFrom(type)) {
                specs.add(new ParamSpec(ParamKind.SESSION, null, type));
            } else if (isOnOpen && EndpointConfig.class.isAssignableFrom(type)) {
                specs.add(new ParamSpec(ParamKind.ENDPOINT_CONFIG, null, type));
            } else if (CloseReason.class.isAssignableFrom(type)) {
                specs.add(new ParamSpec(ParamKind.CLOSE_REASON, null, type));
            } else if (Throwable.class.isAssignableFrom(type)) {
                specs.add(new ParamSpec(ParamKind.THROWABLE, null, type));
            } else {
                PathParam pathParam = getAnnotation(annotations[i], PathParam.class);
                if (pathParam != null) {
                    specs.add(new ParamSpec(ParamKind.PATH_PARAM, pathParam.value(), type));
                } else {
                    specs.add(new ParamSpec(ParamKind.MESSAGE, null, type));
                }
            }
        }
        return specs;
    }

    private static <A extends Annotation> A getAnnotation(Annotation[] annotations, Class<A> annotationType) {
        for (Annotation annotation : annotations) {
            if (annotationType.isInstance(annotation)) {
                return annotationType.cast(annotation);
            }
        }
        return null;
    }

    private static ParamSpec findMessageParam(List<ParamSpec> params) {
        for (ParamSpec spec : params) {
            if (spec.kind == ParamKind.MESSAGE) {
                return spec;
            }
        }
        return null;
    }

    // ===================== 渚挎嵎璁块棶 =====================

    public Class<?> getEndpointClass() {
        return endpointClass;
    }

    public String getPath() {
        return path;
    }

    public String[] getSubprotocols() {
        return subprotocols;
    }

    public List<Class<? extends Decoder>> getDecoders() {
        return decoders;
    }

    public List<Class<? extends Encoder>> getEncoders() {
        return encoders;
    }

    public Class<? extends ServerEndpointConfig.Configurator> getConfiguratorClass() {
        return configuratorClass;
    }

    public Method getOnOpen() {
        return onOpen;
    }

    public Method getOnMessage() {
        return onMessage;
    }

    public Method getOnClose() {
        return onClose;
    }

    public Method getOnError() {
        return onError;
    }

    public List<ParamSpec> getOnOpenParams() {
        return onOpenParams;
    }

    public List<ParamSpec> getOnMessageParams() {
        return onMessageParams;
    }

    public List<ParamSpec> getOnCloseParams() {
        return onCloseParams;
    }

    public List<ParamSpec> getOnErrorParams() {
        return onErrorParams;
    }

    public ParamSpec getMessageParam() {
        return messageParam;
    }

    /** 绔偣绫绘槸鍚︽湁鍙敤鐨勬棤鍙傛瀯閫狅紙setAccessible 鍚庡彲鐢紝鍖呯鏈夌被浜︽敮鎸侊級銆?*/
    public boolean isInstantiable() {
        return !Modifier.isAbstract(endpointClass.getModifiers())
                && hasNoArgConstructor(endpointClass);
    }

    private static boolean hasNoArgConstructor(Class<?> clazz) {
        try {
            clazz.getDeclaredConstructor();
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * 鏄惁涓烘秷鎭綋绫诲瀷锛圫tring/byte[]/ByteBuffer/PongMessage/POJO锛夈€?
     * 闈炴枃鏈?浜岃繘鍒跺熀纭€绫诲瀷鍗宠涓?POJO锛岄渶瑕?Decoder 鏀寔銆?
     */
    public static boolean isPlainMessageType(Class<?> type) {
        return String.class == type
                || byte[].class == type
                || ByteBuffer.class == type
                || PongMessage.class.isAssignableFrom(type);
    }

    /**
     * 鏌ユ壘鍖归厤缁欏畾娑堟伅浣撶被鍨嬬殑 {@link Decoder} 绫汇€?
     *
     * @param messageType 娑堟伅浣?POJO 绫诲瀷
     * @param isText      鏄惁涓烘枃鏈抚
     * @return 鍖归厤鐨?Decoder 绫伙紱鏃犲尮閰嶈繑鍥?null
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Class<? extends Decoder> findDecoder(Class<?> messageType, boolean isText) {
        for (Class<? extends Decoder> decoderClass : decoders) {
            Class<?> decodedType = resolveDecoderType(decoderClass, isText);
            if (decodedType != null && decodedType.isAssignableFrom(messageType)) {
                return decoderClass;
            }
        }
        return null;
    }

    /**
     * 瑙ｆ瀽 Decoder 娉涘瀷鍙傛暟瀵瑰簲鐨勬秷鎭綋绫诲瀷銆?
     * <p>鍙敮鎸?{@link Decoder.Text}/{@link Decoder.Binary}锛屾祦寮?Decoder 棣栨湡涓嶆敮鎸併€?/p>
     */
    @SuppressWarnings({"rawtypes"})
    private static Class<?> resolveDecoderType(Class<? extends Decoder> decoderClass, boolean isText) {
        Class<?> iface = isText ? Decoder.Text.class : Decoder.Binary.class;
        ResolvableType rt = ResolvableType.forClass(decoderClass).as(iface);
        if (rt.hasGenerics()) {
            return rt.getGeneric(0).resolve();
        }
        return null;
    }

    /**
     * 鏌ユ壘鍖归厤瀵硅薄绫诲瀷鐨?{@link Encoder} 绫汇€?
     * <p>鍙敮鎸?{@link Encoder.Text}/{@link Encoder.Binary}锛屾祦寮?Encoder 棣栨湡涓嶆敮鎸併€?/p>
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Class<? extends Encoder> findEncoder(Class<?> objectType, boolean isText) {
        for (Class<? extends Encoder> encoderClass : encoders) {
            Class<?> iface = isText ? Encoder.Text.class : Encoder.Binary.class;
            ResolvableType rt = ResolvableType.forClass(encoderClass).as(iface);
            if (rt.hasGenerics()) {
                Class<?> encodedType = rt.getGeneric(0).resolve();
                if (encodedType != null && encodedType.isAssignableFrom(objectType)) {
                    return encoderClass;
                }
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "JsrEndpointMetadata{path='" + path + "', endpointClass=" + endpointClass.getName() + '}';
    }
}

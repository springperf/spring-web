package io.springperf.web.websocket.jsr;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Decoder;
import jakarta.websocket.Encoder;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.PongMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
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
 * JSR-356 {@link ServerEndpoint} 注解端点的元数据。
 *
 * <p>启动时一次性解析端点类，将 {@code @ServerEndpoint} 注解属性与
 * {@code @OnOpen/@OnMessage/@OnClose/@OnError} 生命周期方法及参数注入规则缓存，
 * 请求路径上零反射、零注解查找（遵循框架性能原则）。</p>
 *
 * @author huangcanda
 * @since 3.5.6
 */
public class JsrEndpointMetadata {

    /** 生命周期方法参数注入类型。 */
    public enum ParamKind {
        /** jakarta.websocket.Session */
        SESSION,
        /** jakarta.websocket.EndpointConfig（仅 @OnOpen） */
        ENDPOINT_CONFIG,
        /** jakarta.websocket.CloseReason（仅 @OnClose） */
        CLOSE_REASON,
        /** Throwable（仅 @OnError） */
        THROWABLE,
        /** 消息体（仅 @OnMessage）：String/ByteBuffer/byte[]/PongMessage/POJO */
        MESSAGE,
        /** @PathParam */
        PATH_PARAM
    }

    /** 单个生命周期方法参数的注入规格。 */
    public static final class ParamSpec {
        public final ParamKind kind;
        /** PATH_PARAM 时注解值；MESSAGE 时为消息体类型 */
        public final String name;
        /** MESSAGE 时的参数类型（String/byte[]/ByteBuffer/PongMessage/POJO） */
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

    /** @OnMessage 消息体参数规格（无消息参数时为 null）。 */
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

    // ===================== 便捷访问 =====================

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

    /** 端点类是否有可用的无参构造（setAccessible 后可用，包私有类亦支持）。 */
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
     * 是否为消息体类型（String/byte[]/ByteBuffer/PongMessage/POJO）。
     * 非文本/二进制基础类型即视为 POJO，需要 Decoder 支持。
     */
    public static boolean isPlainMessageType(Class<?> type) {
        return String.class == type
                || byte[].class == type
                || ByteBuffer.class == type
                || PongMessage.class.isAssignableFrom(type);
    }

    /**
     * 查找匹配给定消息体类型的 {@link Decoder} 类。
     *
     * @param messageType 消息体 POJO 类型
     * @param isText      是否为文本帧
     * @return 匹配的 Decoder 类；无匹配返回 null
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Class<? extends Decoder> findDecoder(Class<?> messageType, boolean isText) {
        for (Class<? extends Decoder> decoderClass : decoders) {
            Class<?> decodedType = resolveDecoderType(decoderClass, isText);
            // JSR-356：解码类型 T 必须可赋值给消息参数类型 P（P.isAssignableFrom(T)），
            // 解码产物才能反射注入 @OnMessage 参数
            if (decodedType != null && messageType.isAssignableFrom(decodedType)) {
                return decoderClass;
            }
        }
        return null;
    }

    /**
     * 解析 Decoder 泛型参数对应的消息体类型。
     * <p>只支持 {@link Decoder.Text}/{@link Decoder.Binary}，流式 Decoder 首期不支持。</p>
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
     * 查找匹配对象类型的 {@link Encoder} 类。
     * <p>只支持 {@link Encoder.Text}/{@link Encoder.Binary}，流式 Encoder 首期不支持。</p>
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

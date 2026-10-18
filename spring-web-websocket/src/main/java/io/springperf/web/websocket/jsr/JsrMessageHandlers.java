package io.springperf.web.websocket.jsr;

import jakarta.websocket.DecodeException;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.PongMessage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 会话级动态 {@link MessageHandler} 注册表：JSR-356 {@code Session#addMessageHandler} 的实现载体。
 * <p>
 * 规范约束（jakarta.websocket 2.1 {@code Session#addMessageHandler}）：每种原生消息类型（text / binary / pong） 最多一个 handler， 重复注册同一类型抛
 * {@link IllegalStateException}；handler 的泛型参数 {@code T} 决定它属于哪种原生类型。
 * </p>
 * <p>
 * 分片取舍：本框架的消息模型是「完整消息」（端点侧 {@code supportsPartialMessages()} 恒为 false），因此 {@link MessageHandler.Partial}
 * 以「整条消息即唯一分片、{@code last=true}」交付——规范明确允许实现自选分片方案。
 * </p>
 * <p>
 * 线程安全：注册/移除可能在任意业务线程（含 {@code @OnOpen}），分发表在 EventLoop 上串行执行，故槽位用 volatile + 注册期加锁。
 * </p>
 *
 * @author huangcanda
 *
 * @since 3.5.8
 */
final class JsrMessageHandlers {

    /** 原生消息类型：文本帧。 */
    static final int TEXT = 0;

    /** 原生消息类型：二进制帧。 */
    static final int BINARY = 1;

    /** 原生消息类型：Pong 帧。 */
    static final int PONG = 2;

    /** 不占任何原生类型（{@code @OnMessage} 无消息参数，或参数类型无法归类）。 */
    static final int NONE = -1;

    private final Object lock = new Object();

    private volatile Slot text;
    private volatile Slot binary;
    private volatile Slot pong;

    /** 注解 {@code @OnMessage} 占用的原生类型；规范下同一类型不能再注册动态 handler。 */
    private boolean annotatedText;
    private boolean annotatedBinary;
    private boolean annotatedPong;

    /**
     * 标记 {@code @OnMessage} 已占用某原生消息类型。
     */
    void markAnnotated(int kind) {
        synchronized (lock) {
            switch (kind) {
                case TEXT:
                    annotatedText = true;
                    break;
                case BINARY:
                    annotatedBinary = true;
                    break;
                case PONG:
                    annotatedPong = true;
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * 注册一个动态 handler。
     *
     * @throws IllegalStateException
     *             该原生类型已被 {@code @OnMessage} 或另一个动态 handler 占用
     */
    void add(int kind, Class<?> type, MessageHandler handler, boolean partial) {
        synchronized (lock) {
            if (isAnnotated(kind)) {
                throw new IllegalStateException("A message handler is already registered for " + kindName(kind)
                        + " messages by the annotated @OnMessage method");
            }
            Slot slot = new Slot(handler, type, partial, kind);
            switch (kind) {
                case TEXT:
                    if (text != null) {
                        throw duplicate(kind);
                    }
                    text = slot;
                    break;
                case BINARY:
                    if (binary != null) {
                        throw duplicate(kind);
                    }
                    binary = slot;
                    break;
                case PONG:
                    if (pong != null) {
                        throw duplicate(kind);
                    }
                    pong = slot;
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported native message type: " + kind);
            }
        }
    }

    /**
     * 按引用移除 handler；不存在时为无操作。
     */
    void remove(MessageHandler handler) {
        synchronized (lock) {
            if (text != null && text.handler == handler) {
                text = null;
            } else if (binary != null && binary.handler == handler) {
                binary = null;
            } else if (pong != null && pong.handler == handler) {
                pong = null;
            }
        }
    }

    /**
     * 已注册的动态 handler 快照（不可修改，符合规范对 {@code getMessageHandlers()} 的要求）。
     * <p>
     * 注解 {@code @OnMessage} 不是 {@link MessageHandler} 实例，只作为类型占位参与重复注册判定，不出现在快照里。
     * </p>
     */
    Set<MessageHandler> snapshot() {
        Slot textSlot = text;
        Slot binarySlot = binary;
        Slot pongSlot = pong;
        if (textSlot == null && binarySlot == null && pongSlot == null) {
            return Collections.emptySet();
        }
        Set<MessageHandler> handlers = new LinkedHashSet<>(3);
        addIfPresent(handlers, textSlot);
        addIfPresent(handlers, binarySlot);
        addIfPresent(handlers, pongSlot);
        return Collections.unmodifiableSet(handlers);
    }

    private static void addIfPresent(Set<MessageHandler> handlers, Slot slot) {
        if (slot != null) {
            handlers.add(slot.handler);
        }
    }

    /**
     * 是否有动态 handler：为空时消息分发完全走注解路径。
     * <p>
     * 由三个槽位直接推导而不是维护计数器：槽位本身已 volatile，省掉一处需要跨线程同步的状态。
     * </p>
     */
    boolean isEmpty() {
        return text == null && binary == null && pong == null;
    }

    /**
     * 交付文本消息。
     *
     * @return true 表示已由动态 handler 消费；false 表示没有 text 类型的动态 handler
     */
    boolean deliverText(String payload, JsrCodecRegistry codecRegistry) throws DecodeException {
        Slot slot = text;
        return slot != null && deliverDecoded(slot, payload, codecRegistry);
    }

    /**
     * 交付二进制消息。
     *
     * @return true 表示已由动态 handler 消费；false 表示没有 binary 类型的动态 handler
     */
    boolean deliverBinary(ByteBuffer payload, JsrCodecRegistry codecRegistry) throws DecodeException {
        Slot slot = binary;
        return slot != null && deliverDecoded(slot, payload, codecRegistry);
    }

    /**
     * 交付 Pong 消息。
     *
     * @return true 表示已由动态 handler 消费；false 表示没有 pong 类型的动态 handler
     */
    boolean deliverPong(PongMessage payload) {
        Slot slot = pong;
        if (slot == null) {
            return false;
        }
        invoke(slot, payload);
        return true;
    }

    private static boolean deliverDecoded(Slot slot, Object payload, JsrCodecRegistry codecRegistry)
            throws DecodeException {
        invoke(slot, payloadFor(slot, payload, codecRegistry));
        return true;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void invoke(Slot slot, Object message) {
        if (slot.partial) {
            ((MessageHandler.Partial) slot.handler).onMessage(message, true);
        } else {
            ((MessageHandler.Whole) slot.handler).onMessage(message);
        }
    }

    /**
     * 把容器侧的载荷转换成 handler 声明的 {@code T}。
     * <p>
     * {@link Reader}/{@link InputStream} 每次给新实例：规范允许实现在回调结束后回收这类对象， 交付独立实例最安全（且只有显式注册了这类 handler 才创建）。
     * </p>
     */
    private static Object payloadFor(Slot slot, Object payload, JsrCodecRegistry codecRegistry) throws DecodeException {
        if (slot.kind == PONG) {
            return payload;
        }
        Class<?> type = slot.type;
        if (payload instanceof String) {
            String text = (String) payload;
            if (type == String.class) {
                return text;
            }
            if (Reader.class.isAssignableFrom(type)) {
                return new StringReader(text);
            }
            return codecRegistry.decodeText(text, type);
        }
        ByteBuffer buffer = (ByteBuffer) payload;
        if (type == ByteBuffer.class) {
            return buffer;
        }
        if (type == byte[].class) {
            return toBytes(buffer);
        }
        if (InputStream.class.isAssignableFrom(type)) {
            return new ByteArrayInputStream(toBytes(buffer));
        }
        return codecRegistry.decodeBinary(buffer, type);
    }

    private static byte[] toBytes(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        return bytes;
    }

    private boolean isAnnotated(int kind) {
        return (kind == TEXT && annotatedText) || (kind == BINARY && annotatedBinary)
                || (kind == PONG && annotatedPong);
    }

    private static IllegalStateException duplicate(int kind) {
        return new IllegalStateException("A message handler is already registered for " + kindName(kind) + " messages");
    }

    private static String kindName(int kind) {
        switch (kind) {
            case TEXT:
                return "text";
            case BINARY:
                return "binary";
            case PONG:
                return "pong";
            default:
                return String.valueOf(kind);
        }
    }

    // ===================== 泛型推断（addMessageHandler(MessageHandler) 无 Class 版） =====================

    /**
     * 从 handler 的泛型声明推断它要处理的消息类型。
     * <p>
     * 规范说明无 {@code Class} 的重载只对「直接继承 {@code Whole}/{@code Partial} 的匿名类」可靠；lambda 或复杂继承下 泛型已被擦除，此时按规范要求抛
     * {@link IllegalStateException}，由调用方改用带 {@code Class} 的重载。
     * </p>
     */
    static Class<?> declaredMessageType(MessageHandler handler) {
        if (!(handler instanceof MessageHandler.Whole) && !(handler instanceof MessageHandler.Partial)) {
            throw new IllegalStateException("Not a Whole or Partial message handler: " + handler.getClass().getName());
        }
        Class<?> target = handler instanceof MessageHandler.Whole ? MessageHandler.Whole.class
                : MessageHandler.Partial.class;
        Type argument = findTypeArgument(handler.getClass(), target);
        if (argument instanceof Class) {
            return (Class<?>) argument;
        }
        throw new IllegalStateException("Cannot determine the message type handled by " + handler.getClass().getName()
                + " (generic type not reified), use addMessageHandler(Class, MessageHandler) instead");
    }

    private static Type findTypeArgument(Class<?> type, Class<?> target) {
        for (Type genericInterface : type.getGenericInterfaces()) {
            Type found = matchTypeArgument(genericInterface, target);
            if (found != null) {
                return found;
            }
        }
        Class<?> superclass = type.getSuperclass();
        return superclass != null && superclass != Object.class ? findTypeArgument(superclass, target) : null;
    }

    private static Type matchTypeArgument(Type candidate, Class<?> target) {
        if (candidate instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) candidate;
            return parameterized.getRawType() == target ? parameterized.getActualTypeArguments()[0] : null;
        }
        if (candidate instanceof Class) {
            // 接口继承（interface MyHandler extends Whole<String>）：继续往上找
            return findTypeArgument((Class<?>) candidate, target);
        }
        return null;
    }

    /** 一个已占用的原生类型槽位。 */
    private static final class Slot {

        private final MessageHandler handler;
        private final Class<?> type;
        private final boolean partial;
        private final int kind;

        Slot(MessageHandler handler, Class<?> type, boolean partial, int kind) {
            this.handler = handler;
            this.type = type;
            this.partial = partial;
            this.kind = kind;
        }
    }
}

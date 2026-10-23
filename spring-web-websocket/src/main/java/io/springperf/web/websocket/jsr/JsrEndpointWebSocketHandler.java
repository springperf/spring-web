package io.springperf.web.websocket.jsr;

import io.springperf.web.websocket.server.WebSocketOriginValidator;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.PongMessage;
import jakarta.websocket.server.ServerEndpointConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.util.RouteMatcher;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * JSR-356 {@code @ServerEndpoint} 注解端点与框架 {@link WebSocketHandler} 之间的适配器。
 * <p>
 * 生命周期翻译：
 * </p>
 * <ul>
 * <li>{@link #afterConnectionEstablished} → 实例化端点 + 调用 {@code @OnOpen}</li>
 * <li>{@link #handleMessage} → Decoder 解码 + 参数注入 + 调用 {@code @OnMessage}</li>
 * <li>{@link #afterConnectionClosed} → 调用 {@code @OnClose}</li>
 * <li>{@link #handleTransportError} → 调用 {@code @OnError}</li>
 * </ul>
 * <p>
 * 由于 {@link WebSocketHandler} 被连接间共享（{@code @Sharable}）， 每次连接的状态（端点实例、JSR Session、编解码器）存放在
 * {@link WebSocketSession#getAttributes()} 中，避免跨连接串扰。
 * </p>
 * <p>
 * {@link ServerEndpointConfig.Configurator} 目前只有 {@code getEndpointInstance} 与 {@code checkOrigin} 生效 （后者经
 * {@link WebSocketOriginValidator} 接入手写握手）；{@code modifyHandshake} 不会被调用——本框架没有 JSR 容器那套握手拦截点，需要改握手响应头请用框架自己的
 * {@code HandshakeInterceptor}。
 * </p>
 *
 * @author huangcanda
 *
 * @since 3.5.6
 */
@Slf4j
public class JsrEndpointWebSocketHandler implements WebSocketHandler, WebSocketOriginValidator {

    private static final String STATE_KEY = JsrEndpointWebSocketHandler.class.getName() + ".state";

    private final JsrEndpointMetadata metadata;
    private final JsrEndpointConfigAdapter config;
    private final ServerEndpointConfig.Configurator configurator;
    private final JsrWebSocketContainer container;
    /** {@code @OnMessage} 实参中消息体的下标；无消息参数时为 -1。 */
    private final int messageArgIndex;

    public JsrEndpointWebSocketHandler(JsrEndpointMetadata metadata, JsrWebSocketContainer container) {
        this.metadata = metadata;
        this.config = new JsrEndpointConfigAdapter(metadata);
        this.configurator = config.getConfigurator();
        this.container = container;
        this.messageArgIndex = indexOfMessageParam(metadata.getOnMessageParams());
        // 回调方法在连接建立时一次性置为 accessible。setAccessible 是 @CallerSensitive，
        // 每次调用都要过模块/安全管理器检查——放在 invoke 里等于每条消息付一次纯开销。
        makeAccessible(metadata.getOnOpen());
        makeAccessible(metadata.getOnMessage());
        makeAccessible(metadata.getOnClose());
        makeAccessible(metadata.getOnError());
    }

    private static void makeAccessible(Method method) {
        if (method != null) {
            method.setAccessible(true);
        }
    }

    private static int indexOfMessageParam(List<JsrEndpointMetadata.ParamSpec> specs) {
        for (int i = 0; i < specs.size(); i++) {
            if (specs.get(i).kind == JsrEndpointMetadata.ParamKind.MESSAGE) {
                return i;
            }
        }
        return -1;
    }

    // ===================== Spring WebSocketHandler =====================

    @Override
    public void afterConnectionEstablished(WebSocketSession springSession) throws Exception {
        Object endpoint = instantiateEndpoint();
        JsrCodecRegistry codecRegistry = new JsrCodecRegistry(config);
        Map<String, String> pathParams = resolvePathParameters(springSession);
        JsrWebSocketSession jsrSession = new JsrWebSocketSession(springSession, container, codecRegistry, pathParams,
                metadata.getPath());
        // 先标记 @OnMessage 占用的原生消息类型，再回调 @OnOpen：@OnOpen 里注册同类型的动态 handler 必须立刻被拒绝
        JsrEndpointMetadata.ParamSpec messageParam = metadata.getMessageParam();
        if (messageParam != null) {
            jsrSession.markAnnotatedMessageType(messageParam.type);
        }
        // 先登记再回调 @OnOpen：JSR 允许 @OnOpen 里就用 getOpenSessions() 广播/自检
        container.registerSession(metadata.getPath(), jsrSession);

        // 实参模板：SESSION / EndpointConfig / @PathParam 都是连接级常量，只有消息槽位逐帧变化，
        // 因此在连接建立时建好数组，消息路径不再每次 new Object[n] + switch 分发。
        Object[] onMessageArgs = resolveArgs(metadata.getOnMessageParams(), jsrSession, config, null, null, null);
        springSession.getAttributes().put(STATE_KEY,
                new JsrConnectionState(endpoint, jsrSession, codecRegistry, onMessageArgs));

        Method onOpen = metadata.getOnOpen();
        if (onOpen != null) {
            Object[] args = resolveArgs(metadata.getOnOpenParams(), jsrSession, config, null, null, null);
            try {
                invoke(endpoint, onOpen, args);
            } catch (Exception ex) {
                // @OnOpen 抛异常 → 外层握手处理器会 close(SERVER_ERROR)，而 open=false 后
                // channelInactive 被跳过，afterConnectionClosed 永不回调 → 必须在此兜底：
                // 回收会话状态与在线会话登记并销毁 Decoder/Encoder（避免连接失败导致 codec/endpoint 实例泄漏）
                springSession.getAttributes().remove(STATE_KEY);
                container.unregisterSession(metadata.getPath(), jsrSession);
                codecRegistry.destroy();
                throw ex;
            }
        }
    }

    @Override
    public void handleMessage(WebSocketSession springSession, WebSocketMessage<?> message) throws Exception {
        JsrConnectionState state = getState(springSession);
        // 动态 handler 优先：某原生消息类型一旦被动态注册，该类型就由它独占（注册阶段已与 @OnMessage 互斥），
        // 未被动态注册的类型仍走注解路径。
        if (state != null) {
            JsrMessageHandlers handlers = state.jsrSession.handlers();
            if (!handlers.isEmpty()) {
                try {
                    if (deliverToMessageHandlers(handlers, message, state.codecRegistry)) {
                        return;
                    }
                } catch (jakarta.websocket.DecodeException ex) {
                    throw ex;
                } catch (RuntimeException ex) {
                    // 与注解路径一致：业务异常归一为 @OnError，不打断后续消息
                    handleEndpointError(state, ex);
                    return;
                }
            }
        }
        Method onMessage = metadata.getOnMessage();
        if (onMessage == null) {
            return;
        }
        JsrEndpointMetadata.ParamSpec messageParam = metadata.getMessageParam();
        Object decoded = decode(message, messageParam, state.codecRegistry);
        // 复用连接级实参模板：帧派发由 channel 的 EventLoop 串行执行，且 Method#invoke 不保留数组引用。
        // 退出时清空消息槽位，避免把上一帧的大 payload 一直挂在连接状态上。
        Object[] args = state.onMessageArgs;
        if (messageArgIndex >= 0) {
            args[messageArgIndex] = decoded;
        }
        Object returnValue;
        try {
            returnValue = invokeWithReturn(state.endpoint, onMessage, args);
        } catch (InvocationTargetException ex) {
            handleEndpointError(state, ex.getTargetException());
            return;
        } finally {
            if (messageArgIndex >= 0) {
                args[messageArgIndex] = null;
            }
        }
        if (returnValue != null) {
            sendReturnValue(springSession, returnValue, state.codecRegistry);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession springSession, Throwable exception) {
        JsrConnectionState state = getState(springSession);
        if (state == null) {
            return;
        }
        handleEndpointError(state, exception);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession springSession, CloseStatus status) throws Exception {
        JsrConnectionState state = (JsrConnectionState) springSession.getAttributes().remove(STATE_KEY);
        if (state == null) {
            return;
        }
        try {
            Method onClose = metadata.getOnClose();
            if (onClose != null) {
                // RFC 6455 允许 4000-4999 应用码，CloseCodes 枚举不含时 getCloseCode 返回 null，
                // 构造 CloseReason 后 @OnClose 调 getCode() 会 NPE——回退到规范通用码
                CloseReason.CloseCode closeCode = CloseReason.CloseCodes.getCloseCode(status.getCode());
                if (closeCode == null) {
                    closeCode = CloseReason.CloseCodes.NO_STATUS_CODE;
                }
                CloseReason reason = new CloseReason(closeCode, status.getReason());
                Object[] args = resolveArgs(metadata.getOnCloseParams(), state.jsrSession, config, null, reason, null);
                invoke(state.endpoint, onClose, args);
            }
        } finally {
            // @OnClose 自身异常也不能跳过在线会话注销与 Decoder/Encoder 销毁
            container.unregisterSession(metadata.getPath(), state.jsrSession);
            state.codecRegistry.destroy();
        }
    }

    /**
     * 恒为 false：框架只向 handler 交付完整消息。因此动态注册的 {@link jakarta.websocket.MessageHandler.Partial} 也按
     * 「整条消息即唯一分片、{@code last=true}」交付（规范允许实现自选分片方案）。
     */
    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    /**
     * JSR-356 的 {@code Configurator#checkOrigin}：在路由层白名单之外再判一次。默认实现返回 true， 因此没有自定义 Configurator 的端点行为不变。
     */
    @Override
    public boolean isOriginAllowed(@Nullable String origin) {
        return configurator.checkOrigin(origin);
    }

    // ===================== 内部实现 =====================

    private Object instantiateEndpoint() {
        try {
            Object instance = configurator.getEndpointInstance(metadata.getEndpointClass());
            if (instance != null) {
                return instance;
            }
        } catch (InstantiationException ex) {
            // 回退到默认实例化
        }
        try {
            return metadata.getEndpointClass().getDeclaredConstructor().newInstance();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to instantiate endpoint " + metadata.getEndpointClass().getName()
                    + ". Provide a ServerEndpointConfig.Configurator with getEndpointInstance().", ex);
        }
    }

    /**
     * 从请求 URI 与端点模板路径提取路径变量（{roomId} → 123）。
     * <p>
     * 不依赖 Spring Session attributes（其中可能混入其他属性），保证语义纯净。
     * </p>
     */
    private Map<String, String> resolvePathParameters(WebSocketSession springSession) {
        RouteMatcher routeMatcher = io.springperf.web.util.PathPatternUtils.getPatternRouteMatcher();
        // getUri()/getPath() 均可为 null（opaque URI 等）：无路径则无路径变量
        java.net.URI uri = springSession.getUri();
        if (uri == null) {
            return java.util.Collections.emptyMap();
        }
        String requestPath = uri.getPath();
        if (requestPath == null) {
            return java.util.Collections.emptyMap();
        }
        RouteMatcher.Route route = routeMatcher.parseRoute(requestPath);
        Map<String, String> variables = routeMatcher.matchAndExtract(metadata.getPath(), route);
        return variables != null ? variables : java.util.Collections.emptyMap();
    }

    /**
     * 把消息交付给动态注册的 handler。
     *
     * @return true 表示该消息已被动态 handler 消费
     */
    private static boolean deliverToMessageHandlers(JsrMessageHandlers handlers, WebSocketMessage<?> message,
            JsrCodecRegistry codecRegistry) throws jakarta.websocket.DecodeException {
        if (message instanceof TextMessage) {
            return handlers.deliverText(((TextMessage) message).getPayload(), codecRegistry);
        }
        if (message instanceof BinaryMessage) {
            return handlers.deliverBinary(((BinaryMessage) message).getPayload(), codecRegistry);
        }
        if (message instanceof org.springframework.web.socket.PongMessage) {
            org.springframework.web.socket.PongMessage pong = (org.springframework.web.socket.PongMessage) message;
            ByteBuffer data = pong.getPayload();
            return handlers.deliverPong((PongMessage) () -> data);
        }
        return false;
    }

    private Object decode(WebSocketMessage<?> message, JsrEndpointMetadata.ParamSpec messageParam,
            JsrCodecRegistry codecRegistry) throws jakarta.websocket.DecodeException {
        if (messageParam == null) {
            return null;
        }
        Class<?> targetType = messageParam.type;
        if (message instanceof TextMessage) {
            return codecRegistry.decodeText(((TextMessage) message).getPayload(), targetType);
        }
        if (message instanceof BinaryMessage) {
            ByteBuffer buf = ((BinaryMessage) message).getPayload();
            return codecRegistry.decodeBinary(buf, targetType);
        }
        if (message instanceof org.springframework.web.socket.PongMessage
                && PongMessage.class.isAssignableFrom(targetType)) {
            org.springframework.web.socket.PongMessage pong = (org.springframework.web.socket.PongMessage) message;
            ByteBuffer data = pong.getPayload();
            return (PongMessage) () -> data;
        }
        return null;
    }

    private Object[] resolveArgs(List<JsrEndpointMetadata.ParamSpec> specs, JsrWebSocketSession jsrSession,
            EndpointConfig config, Object message, CloseReason closeReason, Throwable error) {
        Object[] args = new Object[specs.size()];
        for (int i = 0; i < specs.size(); i++) {
            JsrEndpointMetadata.ParamSpec spec = specs.get(i);
            switch (spec.kind) {
                case SESSION:
                    args[i] = jsrSession;
                    break;
                case ENDPOINT_CONFIG:
                    args[i] = config;
                    break;
                case CLOSE_REASON:
                    args[i] = closeReason;
                    break;
                case THROWABLE:
                    args[i] = error;
                    break;
                case MESSAGE:
                    args[i] = message;
                    break;
                case PATH_PARAM:
                    args[i] = jsrSession.getPathParameters().get(spec.name);
                    break;
                default:
                    args[i] = null;
            }
        }
        return args;
    }

    private void invoke(Object endpoint, Method method, Object[] args) throws Exception {
        // accessible 已在构造期设置（见 makeAccessible），此处不再每条消息重复设置
        method.invoke(endpoint, args);
    }

    private Object invokeWithReturn(Object endpoint, Method method, Object[] args) throws Exception {
        return method.invoke(endpoint, args);
    }

    /**
     * 将 {@code @OnMessage} 返回值发送回客户端（JSR-356 语义）。
     * <p>
     * String → 文本帧；byte[]/ByteBuffer → 二进制帧；其他类型经 {@link Encoder} 编码。
     * </p>
     */
    private void sendReturnValue(WebSocketSession springSession, Object returnValue, JsrCodecRegistry codecRegistry)
            throws Exception {
        if (returnValue instanceof String) {
            springSession.sendMessage(new TextMessage((String) returnValue));
            return;
        }
        if (returnValue instanceof byte[]) {
            springSession.sendMessage(new BinaryMessage(java.nio.ByteBuffer.wrap((byte[]) returnValue)));
            return;
        }
        if (returnValue instanceof java.nio.ByteBuffer) {
            springSession.sendMessage(new BinaryMessage((java.nio.ByteBuffer) returnValue));
            return;
        }
        JsrCodecRegistry.EncodedPayload payload = codecRegistry.encode(returnValue, true);
        if (payload.isText()) {
            springSession.sendMessage(new TextMessage(payload.getText()));
        } else {
            springSession.sendMessage(new BinaryMessage(payload.getBinary()));
        }
    }

    private void handleEndpointError(JsrConnectionState state, Throwable error) {
        Method onError = metadata.getOnError();
        if (onError == null) {
            log.warn("Unhandled WebSocket endpoint error for {}: {}", metadata.getEndpointClass().getName(),
                    error.toString());
            return;
        }
        try {
            Object[] args = resolveArgs(metadata.getOnErrorParams(), state.jsrSession, config, null, null, error);
            invoke(state.endpoint, onError, args);
        } catch (Exception ex) {
            // @OnError 自身异常不再上抛（避免经 handleTransportError 递归），记录日志
            log.error("Error in @OnError handler of {}", metadata.getEndpointClass().getName(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private JsrConnectionState getState(WebSocketSession springSession) {
        return (JsrConnectionState) springSession.getAttributes().get(STATE_KEY);
    }

    /**
     * 包内测试钩子：连接上复用的 {@code @OnMessage} 实参模板。
     * <p>
     * 用于验证「模板不逐消息重建、退出时清空消息槽位」这一性能契约（生产代码不调用）。
     * </p>
     */
    Object[] onMessageArgsOf(WebSocketSession springSession) {
        JsrConnectionState state = getState(springSession);
        return state != null ? state.onMessageArgs : null;
    }

    /** 单连接状态。 */
    private static final class JsrConnectionState {
        final Object endpoint;
        final JsrWebSocketSession jsrSession;
        final JsrCodecRegistry codecRegistry;
        /** {@code @OnMessage} 实参模板：常量槽位已填好，消息槽位逐帧覆盖后清空。 */
        final Object[] onMessageArgs;

        JsrConnectionState(Object endpoint, JsrWebSocketSession jsrSession, JsrCodecRegistry codecRegistry,
                Object[] onMessageArgs) {
            this.endpoint = endpoint;
            this.jsrSession = jsrSession;
            this.codecRegistry = codecRegistry;
            this.onMessageArgs = onMessageArgs;
        }
    }
}

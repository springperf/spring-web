package io.springperf.web.websocket.jsr;

import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.PongMessage;
import javax.websocket.server.ServerEndpointConfig;
import lombok.extern.slf4j.Slf4j;
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
 * JSR-356 {@code @ServerEndpoint} 娉ㄨВ绔偣涓庢鏋?{@link WebSocketHandler} 涔嬮棿鐨勯€傞厤鍣ㄣ€?
 *
 * <p>鐢熷懡鍛ㄦ湡缈昏瘧锛?/p>
 * <ul>
 *   <li>{@link #afterConnectionEstablished} 鈫?瀹炰緥鍖栫鐐?+ 璋冪敤 {@code @OnOpen}</li>
 *   <li>{@link #handleMessage} 鈫?Decoder 瑙ｇ爜 + 鍙傛暟娉ㄥ叆 + 璋冪敤 {@code @OnMessage}</li>
 *   <li>{@link #afterConnectionClosed} 鈫?璋冪敤 {@code @OnClose}</li>
 *   <li>{@link #handleTransportError} 鈫?璋冪敤 {@code @OnError}</li>
 * </ul>
 *
 * <p>鐢变簬 {@link WebSocketHandler} 琚繛鎺ラ棿鍏变韩锛坽@code @Sharable}锛夛紝
 * 姣忔杩炴帴鐨勭姸鎬侊紙绔偣瀹炰緥銆丣SR Session銆佺紪瑙ｇ爜鍣級瀛樻斁鍦?
 * {@link WebSocketSession#getAttributes()} 涓紝閬垮厤璺ㄨ繛鎺ヤ覆鎵般€?/p>
 *
 * @author huangcanda
 * @since 3.5.6
 */
@Slf4j
public class JsrEndpointWebSocketHandler implements WebSocketHandler {

    private static final String STATE_KEY = JsrEndpointWebSocketHandler.class.getName() + ".state";

    private final JsrEndpointMetadata metadata;
    private final JsrEndpointConfigAdapter config;
    private final ServerEndpointConfig.Configurator configurator;
    private final JsrWebSocketContainer container;

    public JsrEndpointWebSocketHandler(JsrEndpointMetadata metadata, JsrWebSocketContainer container) {
        this.metadata = metadata;
        this.config = new JsrEndpointConfigAdapter(metadata);
        this.configurator = config.getConfigurator();
        this.container = container;
    }

    // ===================== Spring WebSocketHandler =====================

    @Override
    public void afterConnectionEstablished(WebSocketSession springSession) throws Exception {
        Object endpoint = instantiateEndpoint();
        JsrCodecRegistry codecRegistry = new JsrCodecRegistry(config);
        Map<String, String> pathParams = resolvePathParameters(springSession);
        JsrWebSocketSession jsrSession =
                new JsrWebSocketSession(springSession, container, codecRegistry, pathParams);

        springSession.getAttributes().put(STATE_KEY, new JsrConnectionState(endpoint, jsrSession, codecRegistry));

        Method onOpen = metadata.getOnOpen();
        if (onOpen != null) {
            Object[] args = resolveArgs(metadata.getOnOpenParams(), jsrSession, config, null, null, null);
            try {
                invoke(endpoint, onOpen, args);
            } catch (Exception ex) {
                // @OnOpen 抛异常 → 外层握手处理器会 close(SERVER_ERROR)，而 open=false 后
                // channelInactive 被跳过，afterConnectionClosed 永不回调 → 必须在此兜底：
                // 回收会话状态并销毁 Decoder/Encoder（避免连接失败导致 codec/endpoint 实例泄漏）
                springSession.getAttributes().remove(STATE_KEY);
                codecRegistry.destroy();
                throw ex;
            }
        }
    }

    @Override
    public void handleMessage(WebSocketSession springSession, WebSocketMessage<?> message) throws Exception {
        JsrConnectionState state = getState(springSession);
        Method onMessage = metadata.getOnMessage();
        if (onMessage == null) {
            return;
        }
        JsrEndpointMetadata.ParamSpec messageParam = metadata.getMessageParam();
        Object decoded = decode(message, messageParam, state.codecRegistry);
        Object[] args = resolveArgs(metadata.getOnMessageParams(), state.jsrSession, config, decoded, null, null);
        Object returnValue;
        try {
            returnValue = invokeWithReturn(state.endpoint, onMessage, args);
        } catch (InvocationTargetException ex) {
            handleEndpointError(state, ex.getTargetException());
            return;
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
            // @OnClose 自身异常也不能跳过 Decoder/Encoder 销毁（避免 codec 实例泄漏）
            state.codecRegistry.destroy();
        }
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    // ===================== 鍐呴儴瀹炵幇 =====================

    private Object instantiateEndpoint() {
        try {
            Object instance = configurator.getEndpointInstance(metadata.getEndpointClass());
            if (instance != null) {
                return instance;
            }
        } catch (InstantiationException ex) {
            // 鍥為€€鍒伴粯璁ゅ疄渚嬪寲
        }
        try {
            return metadata.getEndpointClass().getDeclaredConstructor().newInstance();
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Unable to instantiate endpoint " + metadata.getEndpointClass().getName()
                            + ". Provide a ServerEndpointConfig.Configurator with getEndpointInstance().", ex);
        }
    }

    /**
     * 浠庤姹?URI 涓庣鐐规ā鏉胯矾寰勬彁鍙栬矾寰勫彉閲忥紙{roomId} 鈫?123锛夈€?
     * <p>涓嶄緷璧?Spring Session attributes锛堝叾涓彲鑳芥贩鍏ュ叾浠栧睘鎬э級锛屼繚璇佽涔夌函鍑€銆?/p>
     */
    private Map<String, String> resolvePathParameters(WebSocketSession springSession) {
        RouteMatcher routeMatcher = io.springperf.web.util.PathPatternUtils.getPatternRouteMatcher();
        String requestPath = springSession.getUri().getPath();
        RouteMatcher.Route route = routeMatcher.parseRoute(requestPath);
        Map<String, String> variables = routeMatcher.matchAndExtract(metadata.getPath(), route);
        return variables != null ? variables : java.util.Collections.emptyMap();
    }

    private Object decode(WebSocketMessage<?> message, JsrEndpointMetadata.ParamSpec messageParam,
                          JsrCodecRegistry codecRegistry) throws javax.websocket.DecodeException {
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
        if (message instanceof org.springframework.web.socket.PongMessage && PongMessage.class.isAssignableFrom(targetType)) {
            org.springframework.web.socket.PongMessage pong =
                    (org.springframework.web.socket.PongMessage) message;
            ByteBuffer data = pong.getPayload();
            return (PongMessage) () -> data;
        }
        return null;
    }

    private Object[] resolveArgs(List<JsrEndpointMetadata.ParamSpec> specs,
                                 JsrWebSocketSession jsrSession,
                                 EndpointConfig config,
                                 Object message,
                                 CloseReason closeReason,
                                 Throwable error) {
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
        method.setAccessible(true);
        method.invoke(endpoint, args);
    }

    private Object invokeWithReturn(Object endpoint, Method method, Object[] args) throws Exception {
        method.setAccessible(true);
        return method.invoke(endpoint, args);
    }

    /**
     * 灏?{@code @OnMessage} 杩斿洖鍊煎彂閫佸洖瀹㈡埛绔紙JSR-356 璇箟锛夈€?
     * <p>String 鈫?鏂囨湰甯э紱byte[]/ByteBuffer 鈫?浜岃繘鍒跺抚锛涘叾浠栫被鍨嬬粡 {@link Encoder} 缂栫爜銆?/p>
     */
    private void sendReturnValue(WebSocketSession springSession, Object returnValue,
                                 JsrCodecRegistry codecRegistry) throws Exception {
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
            // @OnError 鑷韩寮傚父涓嶅啀涓婃姏锛堥伩鍏嶇粡 handleTransportError 閫掑綊锛夛紝璁板綍鏃ュ織
            log.error("Error in @OnError handler of {}", metadata.getEndpointClass().getName(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private JsrConnectionState getState(WebSocketSession springSession) {
        return (JsrConnectionState) springSession.getAttributes().get(STATE_KEY);
    }

    /** 鍗曡繛鎺ョ姸鎬併€?*/
    private static final class JsrConnectionState {
        final Object endpoint;
        final JsrWebSocketSession jsrSession;
        final JsrCodecRegistry codecRegistry;

        JsrConnectionState(Object endpoint, JsrWebSocketSession jsrSession, JsrCodecRegistry codecRegistry) {
            this.endpoint = endpoint;
            this.jsrSession = jsrSession;
            this.codecRegistry = codecRegistry;
        }
    }
}

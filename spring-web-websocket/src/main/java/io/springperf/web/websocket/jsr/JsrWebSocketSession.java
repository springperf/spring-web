package io.springperf.web.websocket.jsr;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Extension;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.RemoteEndpoint;
import io.springperf.web.websocket.server.NettyWebSocketSession;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.springframework.lang.Nullable;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JSR-356 {@link Session} 实现，包装底层 Spring {@link WebSocketSession}（Netty 管道）。
 * <p>
 * 职责映射：
 * </p>
 * <ul>
 * <li>发送 → 委托 {@link WebSocketSession#sendMessage}（文本/二进制/Ping/Pong）</li>
 * <li>关闭 → 委托 {@link WebSocketSession#close(CloseStatus)}</li>
 * <li>路径变量 → 握手阶段由 RouteMatcher 提取后注入</li>
 * <li>{@code addMessageHandler} → 会话级动态注册（每种原生消息类型一个，与 {@code @OnMessage} 互斥）</li>
 * </ul>
 *
 * @author huangcanda
 *
 * @since 3.5.6
 */
public class JsrWebSocketSession implements Session {

    private final WebSocketSession springSession;
    /**
     * 底层 Netty 会话（提供「写但不 flush」的批处理原语）。构造期解析一次，避免每次发送做类型判定； 非 Netty 实现（测试桩、其他容器）为 {@code null}，批处理退化为逐条立即发送。
     */
    private final NettyWebSocketSession nettySession;
    private final JsrWebSocketContainer container;
    private final JsrCodecRegistry codecRegistry;
    /** 动态 {@code addMessageHandler} 注册表（每连接一份；空表时分发零额外开销）。 */
    private final JsrMessageHandlers messageHandlers = new JsrMessageHandlers();
    /** 查询参数缓存：握手后不变，懒解析一次（volatile 因为有跨线程读取）。 */
    private volatile Map<String, List<String>> requestParameters;
    private final Map<String, String> pathParameters;
    /** endpoint 的声明路径；{@code null} 表示该会话未关联 endpoint（测试直建），{@code getOpenSessions} 返回空集。 */
    @Nullable
    private final String endpointPath;
    private final Map<String, Object> userProperties = new ConcurrentHashMap<>();

    private final JsrRemoteEndpointBasic basicRemote;
    private final JsrRemoteEndpointAsync asyncRemote;

    private volatile long maxIdleTimeout = -1;

    public JsrWebSocketSession(WebSocketSession springSession, JsrWebSocketContainer container,
            JsrCodecRegistry codecRegistry, Map<String, String> pathParameters) {
        this(springSession, container, codecRegistry, pathParameters, null);
    }

    /**
     * @param endpointPath
     *            endpoint 的声明路径，用于 {@link #getOpenSessions()} 的「同一 endpoint」判定；传 null 表示不参与
     */
    public JsrWebSocketSession(WebSocketSession springSession, JsrWebSocketContainer container,
            JsrCodecRegistry codecRegistry, Map<String, String> pathParameters, @Nullable String endpointPath) {
        this.springSession = springSession;
        this.nettySession = springSession instanceof NettyWebSocketSession ? (NettyWebSocketSession) springSession
                : null;
        this.container = container;
        this.codecRegistry = codecRegistry;
        this.pathParameters = new HashMap<>(pathParameters);
        this.endpointPath = endpointPath;
        this.basicRemote = new JsrRemoteEndpointBasic(this, codecRegistry);
        this.asyncRemote = new JsrRemoteEndpointAsync(this, codecRegistry);
    }

    public WebSocketSession getSpringSession() {
        return springSession;
    }

    /**
     * 发送消息，{@code flush=false} 时由底层 Netty 会话把帧暂存到 outbound buffer，等 {@link #flushBatch()} 统一刷出。
     * <p>
     * 底层不是 Netty 实现时没有「暂存」能力，退化为逐条立即发送（语义正确，只是拿不到批处理收益）。
     * </p>
     */
    void send(WebSocketMessage<?> message, boolean flush) throws IOException {
        if (nettySession == null || flush) {
            springSession.sendMessage(message);
        } else {
            nettySession.sendMessage(message, false);
        }
    }

    /**
     * 同步发送（JSR-356 {@code RemoteEndpoint.Basic} 语义：返回时消息已交给传输层）。
     * <p>
     * 底层不是 Netty 实现时退化为 {@link WebSocketSession#sendMessage}（Spring 契约本身不保证完成）。
     * </p>
     */
    void sendSync(WebSocketMessage<?> message, boolean flush) throws IOException {
        if (nettySession == null) {
            springSession.sendMessage(message);
        } else {
            nettySession.sendMessageAndWait(message, flush);
        }
    }

    /**
     * 发送完成后按 JSR 契约把二进制 buffer 的 position 推进到 limit（表示数据已被消费）。
     * <p>
     * 只在本实现确能保证「返回/完成时消息已交给传输层」时才推进：底层不是 Netty 时退化走的 Spring {@code sendMessage} 不提供完成保证，提前推进会让底层实现读到空数据。
     * </p>
     */
    void markBufferConsumed(ByteBuffer data) {
        if (nettySession != null) {
            data.position(data.limit());
        }
    }

    /**
     * 异步发送，返回的 future 在帧写入后完成（{@code RemoteEndpoint.Async} 用）。
     * <p>
     * 底层不是 Netty 实现时只能退化为「调用返回即完成」——Spring 的 sendMessage 不提供完成信号。
     * </p>
     */
    java.util.concurrent.CompletableFuture<Void> sendAsync(WebSocketMessage<?> message, boolean flush) {
        if (nettySession != null) {
            return nettySession.sendMessageCompletion(message, flush);
        }
        try {
            springSession.sendMessage(message);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException ex) {
            java.util.concurrent.CompletableFuture<Void> failed = new java.util.concurrent.CompletableFuture<>();
            failed.completeExceptionally(ex);
            return failed;
        }
    }

    /**
     * 刷出批处理中暂存的帧；底层非 Netty 实现时为无操作（每帧都已立即发送）。
     */
    void flushBatch() {
        if (nettySession != null) {
            nettySession.flushBatch();
        }
    }

    /** RFC 6455 §5.5：控制帧载荷上限 125 字节。 */
    static final int MAX_CONTROL_FRAME_PAYLOAD = 125;

    /**
     * 校验 Ping/Pong 的载荷长度。JSR-356 规定超限抛 {@link IllegalArgumentException}：超限控制帧是协议违规，
     * 对端会直接以协议错误断开连接，所以必须在发送前拒绝，而不是把非法帧写出去。
     */
    static void checkControlFramePayload(ByteBuffer applicationData) {
        if (applicationData != null && applicationData.remaining() > MAX_CONTROL_FRAME_PAYLOAD) {
            throw new IllegalArgumentException("Control frame payload must not exceed " + MAX_CONTROL_FRAME_PAYLOAD
                    + " bytes, but was " + applicationData.remaining());
        }
    }

    @Override
    public WebSocketContainer getContainer() {
        return container;
    }

    /**
     * 按 handler 的泛型声明推断消息类型后注册。
     * <p>
     * 规范：该重载只对直接继承 {@code Whole}/{@code Partial} 的匿名类可靠，lambda 等泛型已擦除的情形必须改用
     * {@link #addMessageHandler(Class, MessageHandler.Whole)}——推断失败会抛 {@link IllegalStateException}。
     * </p>
     */
    @Override
    public void addMessageHandler(MessageHandler handler) throws IllegalStateException {
        Class<?> type = JsrMessageHandlers.declaredMessageType(handler);
        boolean partial = handler instanceof MessageHandler.Partial;
        register(type, handler, partial);
    }

    @Override
    public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Whole<T> handler) {
        register(clazz, handler, false);
    }

    @Override
    public <T> void addMessageHandler(Class<T> clazz, MessageHandler.Partial<T> handler) {
        register(clazz, handler, true);
    }

    @Override
    public Set<MessageHandler> getMessageHandlers() {
        return messageHandlers.snapshot();
    }

    @Override
    public void removeMessageHandler(MessageHandler handler) {
        // 已关闭会话上做清理不应报错（@OnClose 里常见），故不校验 isOpen
        messageHandlers.remove(handler);
    }

    /**
     * 注册动态 handler：先按类型判定它占哪种原生消息类型，再交给注册表做重复占用校验。
     *
     * @throws IllegalStateException
     *             会话已关闭，或该原生类型已被占用
     * @throws IllegalArgumentException
     *             {@code type} 既不是内建消息类型、也没有对应 Decoder
     */
    private void register(Class<?> type, MessageHandler handler, boolean partial) {
        if (!isOpen()) {
            throw new IllegalStateException("Cannot add a message handler to a closed session");
        }
        int kind = resolveKind(type, partial);
        if (kind == JsrMessageHandlers.NONE) {
            throw new IllegalArgumentException("No Decoder configured for message type " + type.getName()
                    + (partial ? " (partial handlers support String, byte[] and ByteBuffer only)"
                            : " (streaming decoders Decoder.TextStream/BinaryStream are not supported)"));
        }
        messageHandlers.add(kind, type, handler, partial);
    }

    /**
     * 消息类型 → 原生消息类型（text / binary / pong）。
     * <p>
     * {@code partial} 只允许规范列出的分片类型（text: String；binary: byte[]/ByteBuffer）。
     * </p>
     */
    private int resolveKind(Class<?> type, boolean partial) {
        if (String.class == type) {
            return JsrMessageHandlers.TEXT;
        }
        if (byte[].class == type || ByteBuffer.class == type) {
            return JsrMessageHandlers.BINARY;
        }
        if (partial) {
            return JsrMessageHandlers.NONE;
        }
        if (Reader.class.isAssignableFrom(type)) {
            return JsrMessageHandlers.TEXT;
        }
        if (InputStream.class.isAssignableFrom(type)) {
            return JsrMessageHandlers.BINARY;
        }
        if (jakarta.websocket.PongMessage.class == type) {
            return JsrMessageHandlers.PONG;
        }
        if (codecRegistry.canDecodeText(type)) {
            return JsrMessageHandlers.TEXT;
        }
        if (codecRegistry.canDecodeBinary(type)) {
            return JsrMessageHandlers.BINARY;
        }
        return JsrMessageHandlers.NONE;
    }

    /**
     * 标记 {@code @OnMessage} 已占用的原生消息类型：同一类型不得再注册动态 handler（规范：每种原生类型最多一个）。
     * <p>
     * 在 {@code @OnOpen} 之前调用，保证 {@code @OnOpen} 里的重复注册能被立即拒绝。
     * </p>
     * <p>
     * 一个参数类型可能同时能由文本帧与二进制帧解码（POJO 同时配了 {@code Decoder.Text} 与 {@code Decoder.Binary}）， 此时两种帧都会交给该注解方法，两类都必须占位——否则动态注册的
     * binary handler 会与注解方法抢同一批帧。
     * </p>
     */
    void markAnnotatedMessageType(Class<?> type) {
        for (int kind : resolveAnnotatedKinds(type)) {
            messageHandlers.markAnnotated(kind);
        }
    }

    /** 注解消息参数可能接收的所有原生消息类型（无可用 Decoder 时为空，不占位）。 */
    private List<Integer> resolveAnnotatedKinds(Class<?> type) {
        if (String.class == type || Reader.class.isAssignableFrom(type)) {
            return Collections.singletonList(JsrMessageHandlers.TEXT);
        }
        if (byte[].class == type || ByteBuffer.class == type || InputStream.class.isAssignableFrom(type)) {
            return Collections.singletonList(JsrMessageHandlers.BINARY);
        }
        if (jakarta.websocket.PongMessage.class == type) {
            return Collections.singletonList(JsrMessageHandlers.PONG);
        }
        List<Integer> kinds = new ArrayList<>(2);
        if (codecRegistry.canDecodeText(type)) {
            kinds.add(JsrMessageHandlers.TEXT);
        }
        if (codecRegistry.canDecodeBinary(type)) {
            kinds.add(JsrMessageHandlers.BINARY);
        }
        return kinds;
    }

    /** 动态 handler 注册表（包内，供消息分发使用）。 */
    JsrMessageHandlers handlers() {
        return messageHandlers;
    }

    @Override
    public String getProtocolVersion() {
        return "13";
    }

    @Override
    public String getNegotiatedSubprotocol() {
        return springSession.getAcceptedProtocol();
    }

    /**
     * 握手协商成功的扩展（当前仅 {@code permessage-deflate}）。
     * <p>
     * 语义要求如实上报：协商成功时框架内部已按扩展装了压缩器/解压器，若这里返回空列表，业务会误判「压缩未生效」。
     * </p>
     */
    @Override
    public List<Extension> getNegotiatedExtensions() {
        List<WebSocketExtension> negotiated = springSession.getExtensions();
        if (negotiated == null || negotiated.isEmpty()) {
            return Collections.emptyList();
        }
        List<Extension> extensions = new ArrayList<>(negotiated.size());
        for (WebSocketExtension extension : negotiated) {
            extensions.add(toJsrExtension(extension));
        }
        return Collections.unmodifiableList(extensions);
    }

    /** Spring 的扩展描述 → JSR-356 扩展（参数 Map 转成 {@link Extension.Parameter} 列表）。 */
    private static Extension toJsrExtension(WebSocketExtension extension) {
        return new JsrExtension(extension.getName(), extension.getParameters());
    }

    /** JSR-356 扩展的不可变实现：参数在构造期转换一次，读取时给拷贝，避免业务改动内部列表。 */
    private static final class JsrExtension implements Extension {

        private final String name;
        private final List<Extension.Parameter> parameters;

        JsrExtension(String name, Map<String, String> sourceParameters) {
            this.name = name;
            List<Extension.Parameter> converted = new ArrayList<>(sourceParameters.size());
            sourceParameters.forEach((parameterName, parameterValue) -> converted
                    .add(new JsrExtensionParameter(parameterName, parameterValue)));
            this.parameters = Collections.unmodifiableList(converted);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public List<Parameter> getParameters() {
            return new ArrayList<>(parameters);
        }
    }

    /** 单个扩展参数。 */
    private static final class JsrExtensionParameter implements Extension.Parameter {

        private final String name;
        private final String value;

        JsrExtensionParameter(String name, String value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getValue() {
            return value;
        }
    }

    @Override
    public boolean isSecure() {
        // getUri()/getScheme() 均可为 null：用常量侧比较，缺失协议视为非安全
        java.net.URI uri = springSession.getUri();
        String scheme = uri != null ? uri.getScheme() : null;
        return scheme != null && "wss".equalsIgnoreCase(scheme);
    }

    @Override
    public boolean isOpen() {
        return springSession.isOpen();
    }

    @Override
    public long getMaxIdleTimeout() {
        return maxIdleTimeout;
    }

    @Override
    public void setMaxIdleTimeout(long milliseconds) {
        this.maxIdleTimeout = milliseconds;
        // 传播到底层 Netty 会话的 pipeline（IdleStateHandler），使会话级空闲超时真正生效
        // （修复前仅赋值字段、空闲超时空操作）。非 Netty 实现（如测试桩）忽略委托。
        if (springSession instanceof NettyWebSocketSession) {
            ((NettyWebSocketSession) springSession).setMaxIdleTimeout(milliseconds);
        }
    }

    @Override
    public void setMaxBinaryMessageBufferSize(int max) {
        springSession.setBinaryMessageSizeLimit(max);
    }

    @Override
    public int getMaxBinaryMessageBufferSize() {
        return springSession.getBinaryMessageSizeLimit();
    }

    @Override
    public void setMaxTextMessageBufferSize(int max) {
        springSession.setTextMessageSizeLimit(max);
    }

    @Override
    public int getMaxTextMessageBufferSize() {
        return springSession.getTextMessageSizeLimit();
    }

    @Override
    public RemoteEndpoint.Async getAsyncRemote() {
        return asyncRemote;
    }

    @Override
    public RemoteEndpoint.Basic getBasicRemote() {
        return basicRemote;
    }

    @Override
    public String getId() {
        return springSession.getId();
    }

    @Override
    public void close() throws IOException {
        springSession.close();
    }

    @Override
    public void close(CloseReason closeStatus) throws IOException {
        CloseStatus status = new CloseStatus(closeStatus.getCloseCode().getCode(), closeStatus.getReasonPhrase());
        springSession.close(status);
    }

    @Override
    public URI getRequestURI() {
        return springSession.getUri();
    }

    /**
     * 查询参数（值按 {@code application/x-www-form-urlencoded} 解码，符合规范）。
     * <p>
     * 懒解析并缓存：同一次握手的 query 不会变，每条消息都重新切分是白费（跨线程读取用 volatile）。
     * </p>
     */
    @Override
    public Map<String, List<String>> getRequestParameterMap() {
        Map<String, List<String>> cached = requestParameters;
        if (cached == null) {
            cached = parseRequestParameters(getQueryString());
            requestParameters = cached;
        }
        return cached;
    }

    private static Map<String, List<String>> parseRequestParameters(@Nullable String query) {
        if (query == null || query.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<String>> params = new HashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf('=');
            String key = idx >= 0 ? pair.substring(0, idx) : pair;
            String value = idx >= 0 ? pair.substring(idx + 1) : "";
            params.computeIfAbsent(decodeParameter(key), k -> new java.util.ArrayList<>()).add(decodeParameter(value));
        }
        return Collections.unmodifiableMap(params);
    }

    static String decodeParameter(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException ex) {
            // UTF-8 是 JVM 必备字符集，走不到这里；真出现说明运行环境损坏，必须暴露
            throw new IllegalStateException("UTF-8 is not supported by this JVM", ex);
        } catch (IllegalArgumentException ex) {
            // 非法百分号编码（如 ?q=%ZZ）：按原值返回，不因为一个坏参数让整条会话读取失败
            return value;
        }
    }

    @Override
    public String getQueryString() {
        // getRawQuery() 无查询串时即为 null（jakarta.websocket 契约允许）；先取 URI 再取值，
        // 避免链式调用被判为可能 NPE
        java.net.URI uri = springSession.getUri();
        return uri != null ? uri.getRawQuery() : null;
    }

    @Override
    public Map<String, String> getPathParameters() {
        return pathParameters;
    }

    @Override
    public Map<String, Object> getUserProperties() {
        return userProperties;
    }

    @Override
    public Principal getUserPrincipal() {
        return springSession.getPrincipal();
    }

    /**
     * 同一 endpoint（按声明路径判定）当前所有打开的会话快照。
     * <p>
     * 快照是刻意的：业务常见写法是遍历 {@code getOpenSessions()} 逐个发消息甚至关闭，返回活集合会让这种用法 撞上并发修改。会话自身不在集合里时为「已关闭」，与 JSR 语义一致。
     * </p>
     */
    @Override
    public Set<Session> getOpenSessions() {
        if (endpointPath == null) {
            return Collections.emptySet();
        }
        return container.openSessionsOf(endpointPath);
    }

    JsrCodecRegistry getCodecRegistry() {
        return codecRegistry;
    }
}

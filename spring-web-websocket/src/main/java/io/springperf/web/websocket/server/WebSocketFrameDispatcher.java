package io.springperf.web.websocket.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 入站 WebSocket 帧的分发与分片聚合。
 * <p>
 * 从 {@link WebSocketRoutingHandler} 拆出：路由/握手与「帧语义」是两件独立的事，前者关心 HTTP 与 pipeline，后者关心 RFC 6455
 * 的帧规则（FIN/continuation、控制帧、size limit、Close 语义）。拆开后两边的改动不再互相干扰。
 * </p>
 * <p>
 * 无状态：连接状态（会话、handler、进行中的分片）都挂在 {@link WebSocketRoutingHandler.WsConnection} 上，且只在 EventLoop 线程读写。
 * </p>
 *
 * @author huangcanda
 *
 * @since 3.5.8
 */
@Slf4j
final class WebSocketFrameDispatcher {

    /** 空载荷常量：Pong 帧不携带应用数据时复用，避免每帧 {@code new byte[0]} + {@code wrap}。 */
    private static final ByteBuffer EMPTY_PAYLOAD = ByteBuffer.allocate(0);

    private WebSocketFrameDispatcher() {
    }

    /**
     * 分发一个入站帧。帧的引用由本方法在 finally 中释放（Close 帧会 retain 一份用于回写）。
     */
    static void dispatch(ChannelHandlerContext ctx, WebSocketFrame frame) {
        WebSocketRoutingHandler.WsConnection connection = ctx.channel().attr(WebSocketRoutingHandler.CONNECTION_KEY)
                .get();
        if (connection == null) {
            // 未完成握手就收到帧：没有可通知的业务，直接断开。
            // 帧必须在这里释放——此分支在下面的 try/finally 之前返回，不释放就会泄漏。
            frame.release();
            ctx.close();
            return;
        }
        NettyWebSocketSession session = connection.session;
        WebSocketHandler handler = connection.handler;

        try {
            if (frame instanceof TextWebSocketFrame || frame instanceof BinaryWebSocketFrame) {
                boolean text = frame instanceof TextWebSocketFrame;
                ByteBuf content = frame.content();
                // 分片进行中又来数据帧即协议违规，与该帧自身 FIN 无关（RFC 6455 §5.4：
                // 分片消息的后续帧必须全是 continuation）。必须先判违规再判 FIN，
                // 否则 FIN=1 的侵入帧会被当成完整消息静默派发，同时丢掉正在聚合的消息。
                if (connection.fragment != null) {
                    failProtocol(ctx, session, handler, "new data frame while a fragmented message is in progress");
                    return;
                }
                if (frame.isFinalFragment()) {
                    // 未分片的完整消息：同样按该 opcode 的 size limit 校验。
                    // decoder 的 maxFrameSize 只能有一个值，取的是 max(textLimit, binaryLimit)，
                    // 因此 text 限制较小时单帧文本会绕过限制（分片路径按各自限制拒绝）——
                    // 不在此处校验，单帧就成了绕过 size limit 的通道。
                    if (!checkMessageSize(ctx, session, handler, text, content)) {
                        return;
                    }
                    dispatchMessage(handler, session, text, content);
                } else {
                    // 分片起始帧（FIN=0）：建立聚合状态；按首片大小预分配，减少扩容复制
                    FragmentState state = new FragmentState(text, ctx.alloc(), Math.max(256, content.readableBytes()));
                    // 先挂到连接状态：超限路径统一由 releaseFragment 释放缓冲并复位
                    connection.fragment = state;
                    if (!appendFragment(ctx, session, handler, state, content, text)) {
                        releaseFragment(connection);
                        return;
                    }
                }
            } else if (frame instanceof ContinuationWebSocketFrame) {
                FragmentState state = connection.fragment;
                if (state == null) {
                    failProtocol(ctx, session, handler, "continuation frame without a fragmented message in progress");
                    return;
                }
                if (!appendFragment(ctx, session, handler, state, frame.content(), state.text)) {
                    releaseFragment(connection);
                    return;
                }
                if (frame.isFinalFragment()) {
                    connection.fragment = null;
                    try {
                        dispatchMessage(handler, session, state.text, state.buffer);
                    } finally {
                        state.release();
                    }
                    return;
                }
            } else if (frame instanceof PingWebSocketFrame) {
                ctx.writeAndFlush(new PongWebSocketFrame(frame.content().retain()));
            } else if (frame instanceof PongWebSocketFrame) {
                handler.handleMessage(session, toPongMessage(((PongWebSocketFrame) frame).content()));
            } else if (frame instanceof CloseWebSocketFrame) {
                CloseWebSocketFrame close = (CloseWebSocketFrame) frame;
                int code = close.statusCode() >= 0 ? close.statusCode() : 1005;
                CloseStatus status = new CloseStatus(code, close.reasonText());
                session.setOpen(false);
                // 与其他三条断连路径（TCP 断开、close()、异常）共用同一幂等入口：
                // 这里先到就用对端给的 status 通知，随后回显并关 channel 时 channelInactive
                // 再进来会被 markCloseNotified 挡掉，不会重复。
                if (session.markCloseNotified()) {
                    try {
                        handler.afterConnectionClosed(session, status);
                    } catch (Exception e) {
                        log.error("afterConnectionClosed failed", e);
                    }
                }
                // RFC 6455 §7.4.1: 服务端必须回显 Close 帧，再关闭 TCP
                ctx.writeAndFlush(close.retain()).addListener(ChannelFutureListener.CLOSE);
                releaseFragment(connection);
                return; // frame released in finally, retained copy lives for write
            }
        } catch (Exception e) {
            log.error("WebSocket handler error", e);
            try {
                handler.handleTransportError(session, e);
            } catch (Exception ex) {
                log.error("TransportError handler failed", ex);
            }
        } finally {
            frame.release();
        }
    }

    /**
     * 派发一条完整消息。分片文本在此一次性解码，保证跨分片的 UTF-8 多字节字符不乱码。
     * <p>
     * 受检异常不在此处吞掉：统一抛给调用方的 try/catch（转 {@code handleTransportError}），避免分片消息的失败被静默丢弃。
     * </p>
     */
    private static void dispatchMessage(WebSocketHandler handler, NettyWebSocketSession session, boolean text,
            ByteBuf content) throws Exception {
        if (text) {
            handler.handleMessage(session, new TextMessage(content.toString(StandardCharsets.UTF_8)));
        } else {
            byte[] bytes = new byte[content.readableBytes()];
            content.readBytes(bytes);
            handler.handleMessage(session, new BinaryMessage(ByteBuffer.wrap(bytes)));
        }
    }

    /**
     * 入站 Pong 帧 → Spring {@link PongMessage}。必须拷贝：帧在 {@code finally} 里 release， 交给业务的 ByteBuffer
     * 不能是帧内容的视图；空载荷仍复用共享常量，避免每帧分配。
     * <p>
     * 修复前这里无条件用 {@code EMPTY_PAYLOAD}，{@code @OnMessage(PongMessage)} 永远拿不到对端回显的数据。
     * </p>
     */
    private static PongMessage toPongMessage(ByteBuf content) {
        if (!content.isReadable()) {
            return new PongMessage(EMPTY_PAYLOAD);
        }
        byte[] bytes = new byte[content.readableBytes()];
        content.getBytes(content.readerIndex(), bytes);
        return new PongMessage(ByteBuffer.wrap(bytes));
    }

    /**
     * 把一个分片的内容追加进聚合缓冲。
     * <p>
     * 上限用 session 的 text/binary message size limit：无界累积会让一个恶意客户端靠无限分片把堆打满。 超限即释放状态、关闭连接并返回 false（调用方应直接 return，交由
     * finally 释放 frame）。
     * </p>
     *
     * @return false 表示已超限并已处理，调用方不应继续
     */
    private static boolean appendFragment(ChannelHandlerContext ctx, NettyWebSocketSession session,
            WebSocketHandler handler, FragmentState state, ByteBuf content, boolean text) {
        int limit = text ? session.getTextMessageSizeLimit() : session.getBinaryMessageSizeLimit();
        int incoming = content.readableBytes();
        if (state.buffer.readableBytes() + incoming > limit) {
            log.warn("WebSocket fragmented message exceeds {} limit ({}), closing session {}", text ? "text" : "binary",
                    limit, session.getId());
            failProtocol(ctx, session, handler, "fragmented message exceeds size limit");
            return false;
        }
        // writeBytes 是拷贝，不接管 content 的引用；原始引用由外层 finally 的 frame.release() 释放
        state.buffer.writeBytes(content);
        return true;
    }

    /**
     * 校验单帧消息是否超出该 opcode 的 size limit，超限即按协议违规关闭连接。
     * <p>
     * 与 {@link #appendFragment} 用同一套限制，保证「切成两片被拒、一次发完被收」这种不一致不再存在。
     * </p>
     *
     * @return false 表示已超限并已处理，调用方不应继续
     */
    private static boolean checkMessageSize(ChannelHandlerContext ctx, NettyWebSocketSession session,
            WebSocketHandler handler, boolean text, ByteBuf content) {
        int limit = text ? session.getTextMessageSizeLimit() : session.getBinaryMessageSizeLimit();
        int size = content.readableBytes();
        if (size <= limit) {
            return true;
        }
        log.warn("WebSocket message exceeds {} limit ({} > {}), closing session {}", text ? "text" : "binary", size,
                limit, session.getId());
        failProtocol(ctx, session, handler, "message exceeds size limit");
        return false;
    }

    /** 释放进行中的分片聚合缓冲并复位连接状态（超限、Close、断连都走这里，避免缓冲泄漏）。 */
    static void releaseFragment(WebSocketRoutingHandler.WsConnection connection) {
        if (connection == null) {
            return;
        }
        FragmentState state = connection.fragment;
        connection.fragment = null;
        if (state != null) {
            state.release();
        }
    }

    private static void failProtocol(ChannelHandlerContext ctx, NettyWebSocketSession session, WebSocketHandler handler,
            String reason) {
        log.warn("WebSocket protocol violation on session {}: {}", session.getId(), reason);
        try {
            handler.handleTransportError(session, new IOException("WebSocket protocol violation: " + reason));
        } catch (Exception e) {
            log.error("TransportError handler failed", e);
        }
        ctx.close();
    }

    /**
     * 分片消息的累积状态。
     * <p>
     * RFC 6455 的分片规则：起始帧 opcode 为 text/binary 且 {@code FIN=0}，中间帧 opcode 为 continuation 且 {@code FIN=0}， 末帧 opcode 为
     * continuation 且 {@code FIN=1}。文本分片必须按<b>字节</b>累积后一次性解码——UTF-8 多字节字符可能跨分片， 逐帧 decode 会产生乱码。
     * </p>
     */
    static final class FragmentState {

        final boolean text;
        final ByteBuf buffer;

        /**
         * @param alloc
         *            通道的分配器（池化）；用 {@code Unpooled.buffer()} 会让大消息分片时一路扩容复制并产生额外 GC
         * @param initialCapacity
         *            按首片大小预分配，避免从默认 256 字节反复扩容
         */
        FragmentState(boolean text, ByteBufAllocator alloc, int initialCapacity) {
            this.text = text;
            this.buffer = alloc.buffer(initialCapacity);
        }

        void release() {
            buffer.release();
        }
    }
}

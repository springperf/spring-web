package io.springperf.web.websocket.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.springframework.lang.Nullable;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;

import java.nio.ByteBuffer;

/**
 * 出站方向的消息 → 帧转换，与入站方向的 {@link WebSocketFrameDispatcher} 对称。
 * <p>
 * 无状态纯函数：不碰 channel、不持有引用（帧的释放责任在写出的那一侧）。
 * </p>
 *
 * @author huangcanda
 *
 * @since 3.5.8
 */
final class WsFrameCodec {

    /** 帧的 rsv 位：压缩由 extension encoder 完成，此处恒为 0（分片与控制帧同样不带 rsv）。 */
    private static final int NO_RSV = 0;

    private WsFrameCodec() {
    }

    /**
     * 把 Spring 消息转成 Netty 帧。
     *
     * @return 帧；消息类型不受支持时返回 {@code null}
     */
    @Nullable
    static WebSocketFrame encode(WebSocketMessage<?> message) {
        if (message instanceof TextMessage) {
            // 透传 isLast：JSR-356 分片发送会用 last=false 标记非尾片，若一律按 final 帧写出，
            // 客户端会把每个分片当作独立完整消息，分片消息即被静默拆解破坏。
            return new TextWebSocketFrame(((TextMessage) message).isLast(), NO_RSV,
                    ((TextMessage) message).getPayload());
        } else if (message instanceof BinaryMessage) {
            // 用 duplicate() 读取：get(byte[]) 会推进 position，若直接读调用方的 buffer，
            // 同一个 ByteBuffer 发第二次就只剩 0 字节（静默丢消息，且不报错）。
            ByteBuffer buf = ((BinaryMessage) message).getPayload().duplicate();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            return new BinaryWebSocketFrame(((BinaryMessage) message).isLast(), NO_RSV, Unpooled.wrappedBuffer(bytes));
        } else if (message instanceof PingMessage) {
            return new PingWebSocketFrame(copyControlPayload(((PingMessage) message).getPayload()));
        } else if (message instanceof PongMessage) {
            return new PongWebSocketFrame(copyControlPayload(((PongMessage) message).getPayload()));
        }
        return null;
    }

    /**
     * 拷贝 Ping/Pong 的应用数据（RFC 6455 §5.5.2/§5.5.3，控制帧载荷上限 125 字节）。
     * <p>
     * 必须拷贝而非用 {@code Unpooled.wrappedBuffer(ByteBuffer)} 共享：JSR 的 {@code sendPing/sendPong} 与 Spring 的
     * {@code PingMessage/PongMessage} 都不约束调用方在返回后不再改动 buffer，而帧要等 flush 才落网（批处理下更晚）， 共享内存会让调用方复用池化 buffer 时写坏待发数据。125
     * 字节以内的拷贝成本可忽略。
     * </p>
     * <p>
     * 修复前这里用无参构造，应用数据被静默丢弃。
     * </p>
     */
    private static ByteBuf copyControlPayload(ByteBuffer payload) {
        ByteBuffer source = payload.duplicate();
        if (!source.hasRemaining()) {
            return Unpooled.EMPTY_BUFFER;
        }
        byte[] bytes = new byte[source.remaining()];
        source.get(bytes);
        return Unpooled.wrappedBuffer(bytes);
    }
}

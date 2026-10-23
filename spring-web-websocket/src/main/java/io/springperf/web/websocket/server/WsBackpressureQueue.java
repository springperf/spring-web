package io.springperf.web.websocket.server;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.web.socket.WebSocketMessage;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 通道不可写时的出站帧暂存队列（背压）。
 * <p>
 * 从 {@link NettyWebSocketSession} 拆出：队列挂在 channel attribute 上、生命周期跟随 channel，本质上不是会话状态； 拆开后「溢出怎么办」与「会话怎么发消息」可以各自独立演进。
 * </p>
 * <p>
 * 线程模型：队列只在 EventLoop 线程读写（所有写入都从 eventLoop 的发送任务进入，排空由 writability 事件驱动）。
 * </p>
 *
 * @author huangcanda
 *
 * @since 3.5.8
 */
@Slf4j
final class WsBackpressureQueue {

    private static final AttributeKey<Queue<WebSocketFrame>> QUEUE_KEY = AttributeKey.valueOf("ws.backpressure.queue");
    /** 背压溢出「已上报」标记：保证同一次溢出只通知业务一次。 */
    private static final AttributeKey<AtomicBoolean> OVERFLOW_KEY = AttributeKey.valueOf("ws.backpressure.overflow");

    /**
     * 背压队列容量上限。
     * <p>
     * 到达上限即关闭连接：WebSocket 无消息级重传，丢帧会让业务状态静默分叉，宁可断开让业务感知（见 {@link #failOnOverflow}）。
     * </p>
     */
    private static final int MAX_FRAMES = 100;

    private WsBackpressureQueue() {
    }

    /**
     * 通道不可写时把消息排入背压队列。
     * <p>
     * 队列满时<b>关闭连接</b>而不是丢弃帧：WebSocket 没有消息级重传，丢一帧会让业务状态不可恢复地分叉， 且丢的如果是"最旧帧"，应用很可能已假设它送达。关闭连接能让业务通过
     * {@code WebSocketHandler#handleTransportError} 明确感知失败， 由业务决定重连与补偿。
     * </p>
     *
     * @return {@code null} 表示已入队；非 {@code null} 表示队列溢出（连接已被关闭），值是应上报给业务的失败原因
     */
    @Nullable
    static Throwable enqueue(NettyWebSocketSession session, WebSocketMessage<?> message) {
        Queue<WebSocketFrame> queue = queueOf(session.channel());
        if (queue.size() >= MAX_FRAMES) {
            return failOnOverflow(session);
        }
        WebSocketFrame frame = WsFrameCodec.encode(message);
        if (frame != null) {
            queue.offer(frame);
        }
        return null;
    }

    /**
     * 排空背压队列。由 {@link WebSocketRoutingHandler} 在通道恢复可写时调用。
     */
    static void drain(Channel channel) {
        Queue<WebSocketFrame> queue = channel.attr(QUEUE_KEY).get();
        if (queue == null || queue.isEmpty())
            return;
        WebSocketFrame frame;
        while ((frame = queue.poll()) != null) {
            channel.writeAndFlush(frame);
            if (channel.bytesBeforeUnwritable() <= 0 && !queue.isEmpty()) {
                break; // 写缓冲区再次满时退出，等下一次 writability 事件
            }
        }
    }

    private static Queue<WebSocketFrame> queueOf(Channel channel) {
        Queue<WebSocketFrame> queue = channel.attr(QUEUE_KEY).get();
        if (queue == null) {
            // ArrayDeque 无逐帧 Node 分配、遍历局部性更好；队列本身只在 eventLoop 线程访问
            Queue<WebSocketFrame> newQueue = new ArrayDeque<>();
            channel.attr(QUEUE_KEY).set(newQueue);
            // 通道关闭时释放队列中未写出的帧（Binary/Text 帧持有 ByteBuf 引用）。
            // 仅 clear() 会泄漏引用；帧始终由 eventLoop 线程串行入队/排空，此处同样安全。
            channel.closeFuture().addListener(f -> {
                WebSocketFrame pending;
                while ((pending = newQueue.poll()) != null) {
                    pending.release();
                }
            });
            return newQueue;
        }
        return queue;
    }

    /**
     * 背压溢出：只在首次触发时上报并关闭连接。
     * <p>
     * 幂等：溢出后队列可能仍被后续已排队的发送任务命中，重复上报会让业务收到多次同一个错误。
     * </p>
     */
    private static Throwable failOnOverflow(NettyWebSocketSession session) {
        Channel channel = session.channel();
        IOException overflow = new IOException("WebSocket outbound queue overflow (" + MAX_FRAMES
                + " frames) on session " + session.getId() + ", closing connection");
        AtomicBoolean notified = channel.attr(OVERFLOW_KEY).get();
        if (notified == null) {
            channel.attr(OVERFLOW_KEY).set(new AtomicBoolean(true));
            log.warn("WebSocket backpressure queue full ({} frames), closing session {} instead of dropping messages",
                    MAX_FRAMES, session.getId());
            session.notifyTransportError(overflow);
            channel.close();
        }
        return overflow;
    }
}

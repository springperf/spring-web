package io.springperf.web.websocket.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 发送完成语义的失败与边界分支：会话已关闭、EventLoop 拒绝、等待被中断、非 IO 失败原因的包装。
 * <p>
 * 这些分支在正常链路（可写通道 + 活跃 EventLoop）下走不到，此前只有「成功路径」被覆盖， 故单列一个测试类，用 mock 的 channel/eventLoop 精确命中。
 * </p>
 */
class NettyWebSocketSessionSendCompletionTest {

    private static NettyWebSocketSession newSession(Channel channel) {
        return new NettyWebSocketSession(channel, URI.create("ws://localhost/ws"), null, null, null, null, null, null);
    }

    /** 非 EventLoop 视角的通道：{@code inEventLoop()=false}，使调用走投递分支。 */
    private static Channel dispatchChannel(EventLoop eventLoop) {
        Channel channel = mock(Channel.class);
        when(channel.eventLoop()).thenReturn(eventLoop);
        when(channel.isActive()).thenReturn(true);
        return channel;
    }

    @Test
    void sendMessageCompletion_closedSession_completesExceptionally() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            NettyWebSocketSession session = newSession(channel);
            session.setOpen(false);

            CompletableFuture<Void> completion = session.sendMessageCompletion(new TextMessage("x"), true);

            assertTrue(completion.isCompletedExceptionally(), "已关闭的会话必须让调用方立刻看到失败，而不是静默排队");
            assertInstanceOf(IOException.class, completion.handle((v, e) -> e).join());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void sendMessageCompletion_eventLoopRejected_completesExceptionally() {
        EventLoop eventLoop = mock(EventLoop.class);
        when(eventLoop.inEventLoop()).thenReturn(false);
        doThrow(new RejectedExecutionException("shutting down")).when(eventLoop).execute(any(Runnable.class));
        Channel channel = dispatchChannel(eventLoop);

        CompletableFuture<Void> completion = newSession(channel).sendMessageCompletion(new TextMessage("x"), true);

        assertTrue(completion.isCompletedExceptionally(), "EventLoop 已终止时不能把消息悄悄丢掉");
        Throwable cause = completion.handle((v, e) -> e).join();
        assertInstanceOf(IOException.class, cause);
        assertNotNull(cause.getCause(), "原始 RejectedExecutionException 必须作为 cause 保留");
    }

    @Test
    void sendMessageAndWait_interrupted_wrapsAndRestoresFlag() throws Exception {
        // execute 什么都不做：帧永远写不出去，等待方只能被中断唤醒
        EventLoop eventLoop = mock(EventLoop.class);
        when(eventLoop.inEventLoop()).thenReturn(false);
        NettyWebSocketSession session = newSession(dispatchChannel(eventLoop));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                session.sendMessageAndWait(new TextMessage("x"));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "ws-send-interrupt-test");
        worker.start();
        worker.interrupt();
        worker.join(5_000);

        assertInstanceOf(IOException.class, failure.get(), "中断必须转成 IOException，符合 Spring 的 sendMessage 契约");
        assertTrue(worker.isInterrupted(), "中断标志必须被恢复，不能把 InterruptedException 吞掉");
    }

    @Test
    void sendMessageAndWait_nonIoFailure_isWrappedWithCause() {
        Throwable writeFailure = new IllegalStateException("boom");
        ChannelFuture failedFuture = mock(ChannelFuture.class);
        when(failedFuture.isSuccess()).thenReturn(false);
        when(failedFuture.cause()).thenReturn(writeFailure);
        // 立即回调 listener：模拟 write 已失败
        when(failedFuture.addListener(any(ChannelFutureListener.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ChannelFutureListener.class).operationComplete(failedFuture);
            return failedFuture;
        });

        EventLoop eventLoop = mock(EventLoop.class);
        when(eventLoop.inEventLoop()).thenReturn(false);
        // 立即执行投递的任务，让失败发生在 await 之前
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(eventLoop).execute(any(Runnable.class));

        Channel channel = dispatchChannel(eventLoop);
        when(channel.isWritable()).thenReturn(true);
        when(channel.writeAndFlush(any())).thenReturn(failedFuture);

        NettyWebSocketSession session = newSession(channel);

        IOException ex = assertThrows(IOException.class, () -> session.sendMessageAndWait(new TextMessage("x")));
        assertEquals(writeFailure, ex.getCause(), "非 IOException 的写失败原因必须作为 cause 保留，不能被抹平");
    }

    @Test
    void setExtensions_null_resetsToEmptyList() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            NettyWebSocketSession session = newSession(channel);
            session.setExtensions(null);

            assertTrue(session.getExtensions().isEmpty(), "null 扩展必须归一为空列表，业务侧才不用判空");
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}

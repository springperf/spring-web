package io.springperf.web.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.netty.channel.embedded.EmbeddedChannel;

/**
 * 读空闲超时处理器（{@code server.http.read-timeout} 的执行者）： 只回收真正空闲的连接——**有请求在途时必须改期再审，不得关闭连接**， 否则慢处理器（慢 SQL / 下游调用 /
 * 异步挂起）的响应会随连接一起被丢弃。
 * <p>
 * 用 {@link EmbeddedChannel} 的虚拟时钟驱动定时器（{@code advanceTimeBy} + {@code runScheduledPendingTasks}），不依赖墙钟等待，因此结果确定。
 * </p>
 */
class ReadIdleTimeoutHandlerTest {

    private static final long TIMEOUT_MILLIS = 100L;

    @Test
    void idleConnection_closedAfterTimeout() {
        EmbeddedChannel ch = new EmbeddedChannel(new ReadIdleTimeoutHandler(TIMEOUT_MILLIS));

        elapseTimer(ch);

        assertThat(ch.isOpen()).as("空闲连接应在读空闲超时后被关闭").isFalse();
    }

    @Test
    void inFlightRequest_survivesMultipleTimeoutWindows_thenClosedOnceItCompletes() {
        EmbeddedChannel ch = new EmbeddedChannel(new ReadIdleTimeoutHandler(TIMEOUT_MILLIS));
        ChannelAttrs attrs = ChannelAttrs.of(ch);
        attrs.pipeliningInFlight = true;

        // 在途：跨多个超时窗口也不得关闭（每次超时都应改期）
        elapseTimer(ch);
        assertThat(ch.isOpen()).as("请求在途时不得被读空闲超时掐断").isTrue();
        elapseTimer(ch);
        assertThat(ch.isOpen()).as("在途请求跨多个窗口仍应存活").isTrue();

        // 请求完成（在途标记复位）→ 连接重新受读空闲约束
        attrs.pipeliningInFlight = false;
        elapseTimer(ch);
        assertThat(ch.isOpen()).as("请求完成后连接应恢复空闲回收").isFalse();
    }

    @Test
    void removedHandler_cancelsTimer() {
        ReadIdleTimeoutHandler handler = new ReadIdleTimeoutHandler(TIMEOUT_MILLIS);
        EmbeddedChannel ch = new EmbeddedChannel(handler);

        ch.pipeline().remove(handler);
        elapseTimer(ch);

        assertThat(ch.isOpen()).as("处理器移除后不得再因它的定时器关闭连接").isTrue();
    }

    /** 推进虚拟时钟并执行到期任务（超时 + 改期）。 */
    private static void elapseTimer(EmbeddedChannel ch) {
        ch.advanceTimeBy(TIMEOUT_MILLIS * 2, TimeUnit.MILLISECONDS);
        ch.runScheduledPendingTasks();
    }
}

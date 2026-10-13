package io.springperf.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;

/**
 * 响应超时「按需装配」语义（{@link WebServerHttpResponse#armTimeoutIfAbsent()} / {@link WebServerHttpResponse#hasTimeoutArmed()}）。
 * <p>
 * 背景：{@code pool.default-execute-mode=eventloop} 下处理器在 EventLoop 上同步执行，而超时任务 也调度在同一
 * EventLoop（{@code ctx.executor().schedule}），处理器执行期间不可能触发 ——
 * 该模式下请求开始不再装配（{@code NettyHttpHandler#armTimeoutOnRequestStart}），改由 池分支 / 异步开始 / 同步段结束兜底三处按需装配。本类锁定按需装配本身的幂等性与关闭语义。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ResponseTimeoutArmingTest {

    @Mock
    private WebContext webContext;
    @Mock
    private ChannelHandlerContext ctx;
    @Mock
    private EventLoop eventLoop;
    @Mock
    private ApplicationProperties props;

    @BeforeEach
    void setUp() {
        lenient().when(webContext.getProps()).thenReturn(props);
        lenient().when(ctx.executor()).thenReturn(eventLoop);
        // writeBytes 等提交路径会写 channel 并挂监听器
        lenient().when(ctx.writeAndFlush(any())).thenAnswer(inv -> mock(ChannelFuture.class));
    }

    @Test
    void armTimeoutIfAbsent_schedulesOnce_andIsIdempotent() {
        when(props.getHttpTimeoutMillis()).thenReturn(60000L);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(eventLoop.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> future);

        NettyServerHttpResponse resp = newResponse();

        assertFalse(resp.hasTimeoutArmed(), "初始状态不应已装配");
        resp.armTimeoutIfAbsent();
        assertTrue(resp.hasTimeoutArmed(), "装配后 hasTimeoutArmed 应为 true");
        resp.armTimeoutIfAbsent();
        resp.armTimeoutIfAbsent();

        verify(eventLoop, times(1)).schedule(any(Runnable.class), eq(60000L), eq(TimeUnit.MILLISECONDS));
    }

    /** 超时关闭（{@code server.http.timeout<=0}）时不装配：不得产生定时任务，也不得误报已装配。 */
    @Test
    void armTimeoutIfAbsent_doesNothingWhenTimeoutDisabled() {
        when(props.getHttpTimeoutMillis()).thenReturn(0L);

        NettyServerHttpResponse resp = newResponse();
        resp.armTimeoutIfAbsent();

        assertFalse(resp.hasTimeoutArmed(), "关闭超时时不应装配");
    }

    /** 提交即取消：提交后 hasTimeoutArmed 归为 false（供「是否需要兜底装配」判定）。 */
    @Test
    void setCommitted_cancelsArmedTimeout() {
        when(props.getHttpTimeoutMillis()).thenReturn(60000L);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        when(eventLoop.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> future);

        NettyServerHttpResponse resp = newResponse();
        resp.armTimeoutIfAbsent();
        assertTrue(resp.hasTimeoutArmed());

        resp.writeBytes("x".getBytes()); // 触发 setCommitted()

        verify(future, times(1)).cancel(false);
    }

    @Test
    void setTimeout_returnsNullWhenDisabled() {
        when(props.getHttpTimeoutMillis()).thenReturn(-1L);

        NettyServerHttpResponse resp = newResponse();

        assertNull(resp.setTimeout(), "关闭超时时 setTimeout() 应返回 null");
    }

    @Test
    void setTimeout_schedulesWhenEnabled() {
        when(props.getHttpTimeoutMillis()).thenReturn(1000L);
        when(eventLoop.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenReturn(mock(ScheduledFuture.class));

        NettyServerHttpResponse resp = newResponse();

        assertNotNull(resp.setTimeout());
    }

    /**
     * 并发「按需装配」至多产生一个定时任务。
     * <p>
     * 装配方是 EventLoop、取消方是业务线程（已提交时）。原先的 check-then-act 无保护且字段非 volatile： 两个线程能各自调度一个定时器，先发布的那个被引用覆盖后永远无法取消 ——
     * 已提交或在途的响应会被它改写成 504。
     * </p>
     */
    @Test
    void concurrentArm_schedulesExactlyOnce() throws Exception {
        when(props.getHttpTimeoutMillis()).thenReturn(60000L);
        when(eventLoop.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> mock(ScheduledFuture.class));

        NettyServerHttpResponse resp = newResponse();
        runConcurrently(8, 200, resp::armTimeoutIfAbsent);

        verify(eventLoop, times(1)).schedule(any(Runnable.class), eq(60000L), eq(TimeUnit.MILLISECONDS));
    }

    /**
     * 并发替换超时任务时，除最后一个之外每个都必须被 cancel —— 不允许存在无法取消的孤儿定时器。
     * <p>
     * 每次 {@code setTimeout} 恰好「取消至多一个旧的 + 装配一个新的」是临界区内原子完成的结果， 故 {@code cancel 次数 == 装配数 - 1} 是确定的。
     * </p>
     */
    @Test
    void concurrentSetTimeout_cancelsEveryReplacedFuture() throws Exception {
        AtomicInteger cancelCount = new AtomicInteger();
        List<ScheduledFuture<?>> created = new ArrayList<>();
        when(eventLoop.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            doAnswer(c -> {
                cancelCount.incrementAndGet();
                return null;
            }).when(future).cancel(anyBoolean());
            synchronized (created) {
                created.add(future);
            }
            return future;
        });

        NettyServerHttpResponse resp = newResponse();
        runConcurrently(8, 50, () -> resp.setTimeout(() -> {
        }, 1000L));

        assertEquals(created.size() - 1, cancelCount.get(), "除末次装配外，每个被替换的超时任务都必须已取消");
    }

    /** 提交相关的清理之后不应再声称已装配：供「是否需要兜底装配」判定。 */
    @Test
    void cancel_disarmsAndAllowsRearming() {
        when(props.getHttpTimeoutMillis()).thenReturn(60000L);
        when(eventLoop.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> mock(ScheduledFuture.class));

        NettyServerHttpResponse resp = newResponse();
        resp.armTimeoutIfAbsent();
        assertTrue(resp.hasTimeoutArmed());

        resp.setTimeout(null, -1);
        assertFalse(resp.hasTimeoutArmed(), "取消后不应再声称已装配");

        resp.armTimeoutIfAbsent();
        assertTrue(resp.hasTimeoutArmed(), "取消后应允许重新装配");
        verify(eventLoop, times(2)).schedule(any(Runnable.class), eq(60000L), eq(TimeUnit.MILLISECONDS));
    }

    /** 起 n 个线程同时执行 action（各 loop 次），全部结束后才返回。 */
    private static void runConcurrently(int n, int loop, Runnable action) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < loop; j++) {
                        action.run();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发任务未在时限内结束");
    }

    private NettyServerHttpResponse newResponse() {
        return new NettyServerHttpResponse(webContext, ctx, true);
    }
}

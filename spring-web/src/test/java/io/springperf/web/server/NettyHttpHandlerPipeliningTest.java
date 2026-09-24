package io.springperf.web.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.springperf.web.context.PropertiesConstant;

/**
 * 同连接排队上限（{@code server.http.max-pipelined-requests}）的判定内核。
 * <p>
 * 达上限的动作是**暂停读取**而非关闭连接或拒绝请求：pipelining 要求响应保序， 无法只拒绝靠后的请求；暂停后未读字节留在 socket 缓冲，由 TCP 窗口形成背压。
 * </p>
 */
class NettyHttpHandlerPipeliningTest {

    @Test
    void shouldPauseReads_boundaryIsReached() {
        int max = PropertiesConstant.HTTP_MAX_PIPELINED_REQUESTS_DEFAULT;
        assertThat(NettyHttpHandler.shouldPauseReads(0, max)).isFalse();
        assertThat(NettyHttpHandler.shouldPauseReads(max - 1, max)).isFalse();
        assertThat(NettyHttpHandler.shouldPauseReads(max, max)).as("恰好等于上限即暂停（入队后判定）").isTrue();
        assertThat(NettyHttpHandler.shouldPauseReads(max + 1, max)).isTrue();
    }

    @Test
    void shouldPauseReads_nonPositiveLimit_neverPauses() {
        assertThat(NettyHttpHandler.shouldPauseReads(10_000, 0)).as("0 = 不限制").isFalse();
        assertThat(NettyHttpHandler.shouldPauseReads(10_000, -1)).as("负值 = 不限制").isFalse();
    }
}

package io.springperf.web.server;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class KeepAliveHandlerTest {

    @Test
    void maxKeepAliveRequests_addsCloseHeaderAtLimit() {
        EmbeddedChannel ch = new EmbeddedChannel(new KeepAliveHandler(0L, 2));
        ch.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        ch.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        FullHttpResponse r1 = ch.readOutbound();
        assertThat(r1.headers().contains(HttpHeaderNames.CONNECTION)).isFalse();

        ch.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        ch.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        FullHttpResponse r2 = ch.readOutbound();
        assertThat(r2.headers().get(HttpHeaderNames.CONNECTION)).isEqualTo(HttpHeaderValues.CLOSE.toString());
    }

    @Test
    void maxKeepAliveRequests_disabled_doesNotAddCloseHeader() {
        EmbeddedChannel ch = new EmbeddedChannel(new KeepAliveHandler(0L, 0));
        ch.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        ch.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        FullHttpResponse r = ch.readOutbound();
        assertThat(r.headers().contains(HttpHeaderNames.CONNECTION)).isFalse();
        assertThat(ch.isOpen()).isTrue();
    }

    @Test
    void keepAliveTimeout_closesWhenIdle() {
        EmbeddedChannel ch = new EmbeddedChannel(new KeepAliveHandler(100L, 0));
        ch.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        ch.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        // 响应完成后调度空闲超时（100ms）
        ch.advanceTimeBy(200L, TimeUnit.MILLISECONDS);
        ch.runScheduledPendingTasks();
        assertThat(ch.isOpen()).isFalse();
    }

    @Test
    void keepAliveTimeout_disabled_keepsOpenWhenIdle() {
        EmbeddedChannel ch = new EmbeddedChannel(new KeepAliveHandler(0L, 0));
        ch.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
        ch.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        ch.advanceTimeBy(200L, TimeUnit.MILLISECONDS);
        ch.runScheduledPendingTasks();
        assertThat(ch.isOpen()).isTrue();
    }
}

package io.springperf.web.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;

/**
 * {@link NettyServerHttpRequest} 的 {@code server.max-parameter-count} 校验： 解析参数（query / form / multipart）后统计值总数，超限抛
 * {@link ParameterLimitExceededException}。
 */
class NettyServerHttpRequestParameterLimitTest {

    private NettyServerHttpRequest buildRequest(String uri, int maxParam) {
        WebContext wc = mock(WebContext.class);
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(wc.getProps()).thenReturn(props);
        when(props.getMaxInMemorySize()).thenReturn(4096);
        when(props.getMaxParameterCount()).thenReturn(maxParam);
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
        return new NettyServerHttpRequest(wc, mock(ChannelHandlerContext.class), req, "/");
    }

    @Test
    void getParameterMap_throwsWhenOverLimit() {
        String uri = "/p?" + IntStream.range(0, 20).mapToObj(i -> "k" + i + "=v" + i).collect(Collectors.joining("&"));
        NettyServerHttpRequest r = buildRequest(uri, 5);
        ParameterLimitExceededException ex = assertThrows(ParameterLimitExceededException.class, r::getParameterMap);
        assertEquals(5, ex.getLimit());
        assertEquals(20, ex.getActual());
    }

    @Test
    void getParameterMap_okWhenWithinLimit() {
        String uri = "/p?" + IntStream.range(0, 5).mapToObj(i -> "k" + i + "=v" + i).collect(Collectors.joining("&"));
        NettyServerHttpRequest r = buildRequest(uri, 5);
        assertDoesNotThrow(r::getParameterMap);
        assertEquals(5, r.getParameterMap().size());
    }

    @Test
    void zeroLimitMeansUnlimited() {
        String uri = "/p?"
                + IntStream.range(0, 1000).mapToObj(i -> "k" + i + "=v" + i).collect(Collectors.joining("&"));
        NettyServerHttpRequest r = buildRequest(uri, 0);
        assertDoesNotThrow(r::getParameterMap);
        assertEquals(1000, r.getParameterMap().size());
    }
}

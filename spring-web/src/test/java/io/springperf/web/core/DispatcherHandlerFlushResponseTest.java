package io.springperf.web.core;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

/**
 * {@link DispatcherHandler} 收尾 {@code flushResponse} 的语义回归。
 * <p>
 * <b>回归点</b>：一次性写出路径（{@code byte[]} → {@code writeBytes}）在解析返回值时已提交响应， 收尾若再调 {@code flush()}，会走进 {@code writeAndFlush}
 * 的「已提交」拒绝分支： 释放缓冲 + 每个请求白打一条 WARN（实测 {@code /core/bytes} 与 {@code /core/large-response} 各 +1 条/请求，而 JSON 端点 +0
 * 条）。已提交即无待刷内容，应直接跳过。
 * </p>
 */
class DispatcherHandlerFlushResponseTest {

    private final DispatcherHandler handler = new DispatcherHandler();

    private static WebServerHttpResponse response(boolean streaming, boolean handled, boolean committed) {
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.isStreaming()).thenReturn(streaming);
        when(resp.isHandled()).thenReturn(handled);
        when(resp.isCommitted()).thenReturn(committed);
        return resp;
    }

    /** 已提交（byte[] 一次性写出）→ 不得再刷（回归用例）。 */
    @Test
    void committedHandledResponse_isNotFlushedAgain() throws Exception {
        WebServerHttpResponse resp = response(false, true, true);

        handler.flushResponse(mock(WebServerHttpRequest.class), resp);

        verify(resp, never()).flush();
    }

    /** 未提交且已处理（缓冲待刷）→ 必须刷。 */
    @Test
    void uncommittedHandledResponse_isFlushed() throws Exception {
        WebServerHttpResponse resp = response(false, true, false);

        handler.flushResponse(mock(WebServerHttpRequest.class), resp);

        verify(resp).flush();
    }

    /** 未处理（例如映射失败前）→ 不刷。 */
    @Test
    void notHandledResponse_isNotFlushed() throws Exception {
        WebServerHttpResponse resp = response(false, false, false);

        handler.flushResponse(mock(WebServerHttpRequest.class), resp);

        verify(resp, never()).flush();
    }
}

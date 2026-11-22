package io.springperf.web.http.support;

import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import lombok.extern.slf4j.Slf4j;

/**
 * 释放 {@link HttpPostRequestDecoder} 内部缓冲的统一入口。
 * <p>
 * Netty 4.1.137 的 {@code destroy()} 执行顺序是：{@code cleanFiles()} → 带 refCnt 守卫的 part 循环 → {@code destroyed = true} →
 * {@code undecodedChunk.release()}。前半段一旦抛异常，后面的 {@code destroyed} 置位与 {@code undecodedChunk} 释放都不会发生——而该 chunk
 * 承载整个请求体（池化 direct memory），仅靠 GC 无法归还，paranoid 级别会报 {@code Created at: HttpPostStandardRequestDecoder.offer}。
 * </p>
 * <p>
 * 之所以可以直接重试：异常来源 {@code DefaultHttpDataFactory.cleanRequestHttpData} 是 {@code requestFileDeleteMap.remove(request)}
 * <b>之后</b> 才逐个 release，失败时本次请求的登记已被清除； 第二次调用不会再命中同一批 data，且 part 循环本身带 refCnt 守卫，故重试一次即可走完全程。 上限固定为 2 次：一次覆盖「首次中断在
 * cleanFiles()」的已知场景，又不会对已经销毁或不可恢复的状态反复重试。
 * </p>
 */
@Slf4j
final class MultipartDecoderReleaser {

    /** 总尝试次数：首次 + 1 次重试。 */
    private static final int MAX_ATTEMPTS = 2;

    private MultipartDecoderReleaser() {
    }

    /**
     * 尽最大努力销毁 decoder：首次失败后重试一次。
     * <p>
     * 仍失败时只告警不抛出——调用方（{@code release()}/{@code abort()}）已无可补偿动作， 且该路径上的残留会被 paranoid 泄漏门禁与 {@code undecodedChunk}
     * 断言同时兜住。
     * </p>
     *
     * @param decoder
     *            待销毁的 decoder，允许为 {@code null}
     */
    static void destroy(HttpPostRequestDecoder decoder) {
        if (decoder == null) {
            return;
        }
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                decoder.destroy();
                return;
            } catch (Exception ex) {
                if (attempt == MAX_ATTEMPTS) {
                    // 中性措辞：此处既可能是真失败，也可能是 decoder 早已销毁（第二次 destroy() 必抛
                    // IllegalStateException），后者并无残留，不能断言一定泄漏。
                    log.warn(
                            "multipart decoder destroy failed after {} attempts; internal buffers may remain unreleased",
                            MAX_ATTEMPTS, ex);
                } else {
                    log.debug("multipart decoder destroy failed on attempt {} of {}, retrying", attempt, MAX_ATTEMPTS,
                            ex);
                }
            }
        }
    }
}

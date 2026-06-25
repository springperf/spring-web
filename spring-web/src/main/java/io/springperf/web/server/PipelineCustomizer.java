package io.springperf.web.server;

import io.netty.channel.ChannelHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 允许其他模块向 Netty pipeline 中注入额外的 {@link ChannelHandler}。
 *
 * <p>提供两个插入点：
 * <ul>
 *   <li>{@link #addBeforeAggregator(ChannelHandler)} — 在 {@code HttpObjectAggregator} 之前</li>
 *   <li>{@link #addAfterAggregator(ChannelHandler)} — 在 {@code HttpObjectAggregator} 之后</li>
 * </ul>
 *
 * <p>示例：WebSocket 模块使用 {@code addAfterAggregator()} 将握手处理器插入聚合器下游，
 * 确保收到 {@link io.netty.handler.codec.http.FullHttpRequest}。
 *
 * @author huangcanda
 * @since 1.0.4
 */
public class PipelineCustomizer {

    private final List<ChannelHandler> beforeAggregatorHandlers = new ArrayList<>();
    private final List<ChannelHandler> afterAggregatorHandlers = new ArrayList<>();

    public PipelineCustomizer addBeforeAggregator(ChannelHandler handler) {
        beforeAggregatorHandlers.add(handler);
        return this;
    }

    public List<ChannelHandler> getBeforeAggregatorHandlers() {
        return Collections.unmodifiableList(beforeAggregatorHandlers);
    }

    public PipelineCustomizer addAfterAggregator(ChannelHandler handler) {
        afterAggregatorHandlers.add(handler);
        return this;
    }

    public List<ChannelHandler> getAfterAggregatorHandlers() {
        return Collections.unmodifiableList(afterAggregatorHandlers);
    }

    public boolean hasHandlers() {
        return !beforeAggregatorHandlers.isEmpty() || !afterAggregatorHandlers.isEmpty();
    }
}

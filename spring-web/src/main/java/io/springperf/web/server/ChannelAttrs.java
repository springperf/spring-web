package io.springperf.web.server;

import java.util.ArrayDeque;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.util.AttributeKey;
import io.springperf.web.http.ConnectionContext;
import io.springperf.web.http.NettyServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

/**
 * 每连接的框架状态持有者（pipelining / 在途请求与响应登记 / 连接上下文 / 压缩标记与 UA）。
 * <p>
 * <b>为什么需要它</b>：Netty 的 {@code DefaultAttributeMap.attr(key)} 按 key <b>线性查找</b>
 * （{@code searchAttributeByKey}：逐槽位身份比较，直到命中或遇到空槽）。框架原先每请求要访问 channel 属性 8~10 次（4 个 pipelining/在途 key + 连接上下文 + 压缩
 * key），每次都重扫属性数组 —— JFR 叶帧 {@code searchAttributeByKey} 35 样本 / 2191（≈1.6%），归因全部落在
 * {@link NettyHttpHandler#channelRead}/{@code handleRequest}/{@code drainPipelined}。 本类把这些状态收进一个对象、只占 <b>1 个
 * attr</b>：每请求在 {@code channelRead} 取一次并沿调用链 传递，其余访问都是<b>字段读写</b>（零扫描）。
 * </p>
 * <p>
 * <b>为什么不是「缓存 Attribute 句柄」</b>（此路已证伪，勿再尝试）：Netty 的 {@code Attribute.set(null)} / {@code Attribute.remove()}
 * 会<b>把条目从属性表删除</b>， 之后 {@code channel.attr(key)} 会<b>新建</b>一个 Attribute 对象 —— 原先缓存的句柄就此失联 （写入不可见）。实测后果：连接复用下
 * {@code COMPRESSION_REQ_UA} 因前一次为 null 被移除， 后续写入落进失联句柄 → 压缩 excluded-user-agents 失效（CompressionE2ETest 捕获）； 更隐蔽的是
 * {@code inFlightRequest.set(null)} 每次请求都会让在途登记失联 → 连接关闭时的异步持有者退场兜底静默失效。
 * </p>
 * <p>
 * <b>生命周期不变式</b>：本对象的 attr 条目只被 {@code set} 一次、<b>永不 remove/set(null)</b>， 与 channel 同生命周期（内容由 GC 随 channel
 * 回收）；因此句柄稳定、状态不会失联。 首次创建<b>只发生在 EventLoop 上</b>（{@code channelRead} 早于任何响应写入）， 故不存在多线程各建一份导致状态分裂的竞态。
 * </p>
 */
public final class ChannelAttrs {

    private static final AttributeKey<ChannelAttrs> HOLDER = AttributeKey.valueOf("springperf.attrs");

    /** 本连接是否有请求在途（HTTP/1.1 pipelining 串行化；读空闲超时据此豁免处理中的请求）。 */
    public boolean pipeliningInFlight;

    /** 处理中期间到达的同连接请求队列（保序，避免响应乱序 / 连接提前关闭丢弃响应）。 */
    public ArrayDeque<FullHttpRequest> pipeliningPending;

    /**
     * 本处理器是否因排队达到上限而暂停了读取（{@code autoRead=false}）。
     * <p>
     * 只在「本处理器暂停过」时才会恢复读取：若用户/PipelineCustomizer 自行关闭了 autoRead， 队列排空不应把它打开。
     * </p>
     */
    public boolean pipeliningReadPaused;

    /**
     * 当前在途响应：连接在响应写出前关闭（客户端中断）时，由 {@code NettyHttpHandler#channelInactive} 兜底释放其未提交 buf（级联收敛为「仅最后一次
     * release」后，需响应侧自持兜底点）。
     */
    public WebServerHttpResponse inFlightResponse;

    /** 当前在途请求：连接关闭时据此找到异步持有者并让其退场（见 releaseOnConnectionClose）。 */
    public NettyServerHttpRequest inFlightRequest;

    /** 连接上下文（背压可写回调）。由响应侧在首次需要时创建。 */
    public ConnectionContext connCtx;

    /** 零拷贝文件响应（writeFile/DefaultFileRegion）置位：整响应透传，不压缩。 */
    public boolean compressionSkip;

    /** 入站请求的 User-Agent：压缩器据此做 excluded-user-agents 排除（响应头不含 UA）。 */
    public String compressionReqUa;

    private ChannelAttrs() {
    }

    /** 取（或首次创建）本连接的状态持有者。仅供「确定已有请求在本连接上执行」的路径调用。 */
    public static ChannelAttrs of(Channel channel) {
        io.netty.util.Attribute<ChannelAttrs> holder = channel == null ? null : channel.attr(HOLDER);
        if (holder == null) {
            // 非标准 Channel 实现（测试替身等）未提供属性表：退化为「每次新建、不共享」，
            // 保证调用方不 NPE；生产路径（Netty Channel）永远走下面的真持有者分支。
            return new ChannelAttrs();
        }
        ChannelAttrs attrs = holder.get();
        if (attrs == null) {
            attrs = new ChannelAttrs();
            holder.set(attrs);
        }
        return attrs;
    }

    /**
     * 取已存在的持有者；不存在返回 {@code null}（<b>不创建</b>）。
     * <p>
     * 用于「可能没有请求执行过」的路径（{@code channelInactive} 收尾、读空闲探测、压缩器与背压 回调）：持有者不存在 ⟺ 本连接从未走过请求路径 ⟺ 相应的排队/在途/压缩状态必然为空，
     * 故可安全跳过；同时避免在空连接与每次读空闲 tick 上无谓分配。
     * </p>
     */
    public static ChannelAttrs ofIfPresent(Channel channel) {
        io.netty.util.Attribute<ChannelAttrs> holder = channel == null ? null : channel.attr(HOLDER);
        return holder == null ? null : holder.get();
    }
}

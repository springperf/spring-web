package io.springperf.web.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.compression.CompressionOptions;
import io.netty.handler.codec.compression.StandardCompressionOptions;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpContentEncoder;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 对齐 Spring Boot {@code server.compression.*} 的响应 gzip 压缩器。
 * 在 Netty 原生 {@link HttpContentCompressor} 之上补充：
 * <ul>
 *   <li>Content-Type 白名单（{@code mime-types}）</li>
 *   <li>User-Agent 正则排除（{@code excluded-user-agents}）</li>
 *   <li>零拷贝文件响应（{@code DefaultFileRegion}）整响应透传，避免给无 HttpObject 体的文件加 gzip 帧而损坏响应</li>
 * </ul>
 * 其余（Accept-Encoding 协商、gzip/deflate 通道、min-response-size 阈值）委托父类。
 *
 * <p>Accept-Encoding 由父类 {@link HttpContentEncoder} 从入站请求头经内部队列提取，无需框架手动传递；
 * HEAD 亦由父类以 {@code ZERO_LENGTH_HEAD} 特殊处理，绝不压缩。</p>
 */
public class SupportHttpContentCompressor extends HttpContentCompressor {

    private final Set<String> mimeTypes;
    private final List<Pattern> excludedUserAgents;
    private ChannelHandlerContext ctx;

    public SupportHttpContentCompressor(CompressionConfig config) {
        super((int) Math.min(config.getMinResponseSizeBytes(), Integer.MAX_VALUE),
                StandardCompressionOptions.gzip(config.getLevel(), 15, 8),
                StandardCompressionOptions.deflate(config.getLevel(), 15, 8));
        this.mimeTypes = config.getMimeTypes();
        this.excludedUserAgents = config.getExcludedUserAgents();
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        super.handlerAdded(ctx);
    }

    @Override
    protected HttpContentEncoder.Result beginEncode(HttpResponse headers, String acceptEncoding) throws Exception {
        // 零拷贝文件响应（writeFile 置位 compressionSkip）：整响应透传，
        // 否则父类会给 DefaultHttpResponse 打 Content-Encoding: gzip，而真正发送的
        // DefaultFileRegion 不经 HttpObject 处理，客户端拿到「gzip 头 + 明文」的损坏响应。
        // 持有者不存在 ⟺ 本连接未走过请求路径 ⟺ 无任何标记可读（与原 attr 空缺等价）
        ChannelAttrs attrs = ctx == null ? null : ChannelAttrs.ofIfPresent(ctx.channel());
        if (attrs != null && attrs.compressionSkip) {
            attrs.compressionSkip = false;
            return null;
        }
        // User-Agent 排除：请求侧的 UA 由连接状态持有者带入（响应头不含 UA）；读后即清
        String ua = null;
        if (attrs != null) {
            ua = attrs.compressionReqUa;
            attrs.compressionReqUa = null;
        }
        // Content-Type 白名单：取主类型（去 ";charset" 等参数），小写比对
        HttpHeaders h = headers.headers();
        String contentType = h.get(HttpHeaderNames.CONTENT_TYPE);
        if (!isCompressibleMime(contentType)) {
            return null;
        }
        // User-Agent 正则排除（命中则跳过压缩）
        if (ua != null) {
            for (Pattern p : excludedUserAgents) {
                if (p.matcher(ua).find()) {
                    return null;
                }
            }
        }
        // 其余交给父类：Accept-Encoding 协商 + gzip/deflate 通道 + min-response-size 阈值
        HttpContentEncoder.Result result = super.beginEncode(headers, acceptEncoding);
        if (result != null) {
            // RFC 7231 §7.1.4：响应表示随 Accept-Encoding 变化时必须声明 Vary，
            // 否则共享缓存可能把 gzip 响应发给不支持 gzip 的客户端（Tomcat 同语义）。
            appendVaryAcceptEncoding(h);
        }
        return result;
    }

    /** 追加 {@code Vary: Accept-Encoding}（已声明则幂等，避免重复值）。 */
    private static void appendVaryAcceptEncoding(HttpHeaders headers) {
        String vary = headers.get(HttpHeaderNames.VARY);
        String acceptEncoding = HttpHeaderNames.ACCEPT_ENCODING.toString();
        if (vary == null || vary.isEmpty()) {
            headers.set(HttpHeaderNames.VARY, acceptEncoding);
        } else if (!vary.toLowerCase(Locale.ROOT).contains(acceptEncoding)) {
            headers.set(HttpHeaderNames.VARY, vary + ", " + acceptEncoding);
        }
    }

    /**
     * Content-Type 主类型（去 ";参数"）是否命中白名单。
     * 快路径：框架产出的 mime 多为小写且无参数，直接精确匹配 {@code Set}，零分配；
     * 仅当精确未命中（含大小写/空白差异）才走 {@code toLowerCase} 兜底，绝大多数响应不进此分支。
     */
    private boolean isCompressibleMime(String contentType) {
        if (contentType == null) {
            return false;
        }
        int sep = contentType.indexOf(';');
        String primary = sep >= 0 ? contentType.substring(0, sep) : contentType;
        // 快路径：小写无参数 mime（如 application/json）精确命中，无字符串分配
        if (mimeTypes.contains(primary)) {
            return true;
        }
        // 兜底：去空白 + 大小写归一后再比（仅在偏离约定格式时触发）
        return mimeTypes.contains(primary.trim().toLowerCase(Locale.ROOT));
    }
}

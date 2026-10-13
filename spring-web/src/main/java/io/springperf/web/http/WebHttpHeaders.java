package io.springperf.web.http;

import io.netty.handler.codec.http.HttpHeaderNames;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;

/**
 * {@link HttpHeaders} 的零拷贝增强视图（2.7.x 版本）。
 *
 * <p><b>与 master 的区别：</b>master 版本为兼容 Spring 7.x（SB 4.x，{@code HttpHeaders}
 * 不再实现 {@code MultiValueMap}）保留了基于 {@code MethodHandle} 反射的
 * {@code asMultiValueMap()} 桥接，并显式 {@code implements MultiValueMap}；2.7.x 基于
 * Spring 5.3，{@code HttpHeaders} 原生实现 {@code MultiValueMap}，无需该反射兼容层，
 * 此处已移除。其余（零拷贝构造器、Content-Type 直通）逐字对齐。</p>
 *
 * <p>保留能力：接收 {@code MultiValueMap} 视图的构造器（持有引用而非拷贝，零拷贝）——
 * 配合 {@link NettyHttpHeadersAdapter} 可得到 Netty headers 的零拷贝视图。</p>
 */
public class WebHttpHeaders extends HttpHeaders {

    /**
     * 底层 Netty headers（可写视图时非 null），供 Content-Type 读写走「{@code HttpHeaderNames}
     * 常量名直通」快路径。
     * <p>
     * JFR（服务端线程，PerfBenchmark.get）显示每响应的 Content-Type 读写是本框架热点：
     * 写侧 {@code setContentType} → Spring {@code HttpHeaders.set(String,String)} → Netty 每次
     * 现造 {@code AsciiString} 名字并重算哈希；读侧 {@code getContentType} 每次按 String 名查。
     * 常量名哈希已缓存，直通可免掉这部分。
     * </p>
     */
    private final io.netty.handler.codec.http.HttpHeaders rawHeaders;

    public WebHttpHeaders() {
        this.rawHeaders = null;
    }

    /**
     * 用已存在的 {@code MultiValueMap} 视图构造，持有引用而非拷贝（零拷贝）。
     * <p>5.3 的 {@code HttpHeaders(MultiValueMap)} 为引用持有
     * （已反编译验证 {@code putfield headers} 无拷贝循环）。传入
     * {@link NettyHttpHeadersAdapter} 即可获得 Netty headers 的零拷贝视图。</p>
     */
    public WebHttpHeaders(MultiValueMap<String, String> headers) {
        super(headers);
        this.rawHeaders = (headers instanceof NettyHttpHeadersAdapter)
                ? ((NettyHttpHeadersAdapter) headers).rawHeadersIfWritable()
                : null;
    }

    // ========================================================================
    // Content-Type 直通（不缓存，见类注释）
    // ========================================================================

    @Override
    public MediaType getContentType() {
        return rawHeaders != null ? parseContentType(rawHeaders) : super.getContentType();
    }

    /** 语义对齐 Spring {@code HttpHeaders.getContentType()}：缺值/空串返回 null，否则 {@code parseMediaType}。 */
    private static MediaType parseContentType(io.netty.handler.codec.http.HttpHeaders raw) {
        String value = raw.get(HttpHeaderNames.CONTENT_TYPE);
        return (value != null && !value.isEmpty()) ? MediaType.parseMediaType(value) : null;
    }

    @Override
    public void setContentType(MediaType mediaType) {
        if (rawHeaders != null) {
            // 语义对齐 Spring HttpHeaders：null 等价 remove；非 null 写 mediaType.toString()
            if (mediaType == null) {
                rawHeaders.remove(HttpHeaderNames.CONTENT_TYPE);
            } else {
                rawHeaders.set(HttpHeaderNames.CONTENT_TYPE, mediaType.toString());
            }
        } else {
            super.setContentType(mediaType);
        }
    }
}

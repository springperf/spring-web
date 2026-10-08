package io.springperf.web.http;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;

import io.netty.handler.codec.http.HttpHeaderNames;

/**
 * {@link HttpHeaders} 子类：当底层存储是可写的 {@link NettyHttpHeadersAdapter} 时，Content-Type 的读写走
 * {@code HttpHeaderNames} 常量名直通Netty，省掉每次按 String 名查找与 {@code AsciiString} 名字重算哈希。
 * <p>
 * <b>不再缓存 {@link #getContentType()} 的解析结果</b>（曾用 {@code cachedContentType} 字段）：
 * 该缓存只能由 {@link #setContentType(MediaType)} 重置，而本类另有 20 个覆写方法
 * （{@code set}/{@code add}/{@code remove}/{@code put}/{@code putAll}/{@code clear} …）
 * 会改动 header 却<b>不</b>重置缓存 —— 业务经 {@code getHeaders().set("Content-Type", v)}
 * 写入后会读到过期值，且该失效缺陷无任何报错，属静默错误。
 * <p>
 * 实测去掉缓存的代价（{@code ServerAllocBenchmark}，json 场景服务端分配）：
 * 5,446 → 5,473 B/op（<b>+27 B/op，+0.5%</b>）。CPU 侧更可忽略：
 * {@code MediaType.parseMediaType("application/json; charset=utf-8")} 单次 50.9 ns，
 * 每请求1~2 次即 51~102 ns，占单请求耗时（6,700 ops/s 下149μs）的<b>0.03%~0.07%</b> ——
 * 比本机吞吐测量噪声（±15%）小两个数量级，不存在可观测损失。
 * <p>
 * 用「确定的正确行为」换「测不出来的微小开销」是划算的权衡。
 * </p>
 * <p>
 * 本类同时实现 {@link MultiValueMap}，与 Spring 6 的 {@code HttpHeaders} 一致：父类已经实现了该接口， 这里的覆写只是为上面那处优化提供入口，其余一律委派 {@code super}。
 * </p>
 */
@SuppressWarnings("deprecation")
public class WebHttpHeaders extends HttpHeaders implements MultiValueMap<String, String> {

    /**
     * 底层 Netty headers（当以可写 {@link NettyHttpHeadersAdapter} 为存储时非 null）： 供 Content-Type 读写走「{@code HttpHeaderNames}
     * 常量名直通」快路径。
     * <p>
     * JFR（服务端线程，PerfBenchmark.get）显示每响应的 Content-Type 读写是本框架热点： 写侧 {@code setContentType} → Spring
     * {@code HttpHeaders.set(String,String)} → Netty 每次 现造 {@code AsciiString} 名字并重算哈希（{@code hashCodeAscii} 12 样本）+
     * {@code setObject} 36 样本；读侧 {@code getContentType} 每次按 String 名查（ {@code HeadersUtils.getAsString} 17
     * 样本）。常量名哈希已缓存，直通可免掉这部分。
     * </p>
     */
    private final io.netty.handler.codec.http.HttpHeaders rawHeaders;

    public WebHttpHeaders() {
        this.rawHeaders = null;
    }

    /**
     * 用已存在的 {@code MultiValueMap} 视图构造，持有引用而非拷贝（零拷贝）。
     * <p>
     * Spring 的 {@code HttpHeaders(MultiValueMap)} 为引用持有（已反编译验证 {@code putfield headers} 无拷贝循环）。 传入
     * {@link NettyHttpHeadersAdapter} 即可获得 Netty headers 的只读零拷贝视图。
     * </p>
     */
    public WebHttpHeaders(MultiValueMap<String, String> headers) {
        super(headers);
        this.rawHeaders = (headers instanceof NettyHttpHeadersAdapter)
                ? ((NettyHttpHeadersAdapter) headers).rawHeadersIfWritable()
                : null;
    }

    // ========================================================================
    // MultiValueMap methods
    // ========================================================================

    @Override
    public String getFirst(String key) {
        return super.getFirst(key);
    }

    @Override
    public void add(String key, String value) {
        super.add(key, value);
    }

    @Override
    public void addAll(String key, List<? extends String> values) {
        super.addAll(key, values);
    }

    @Override
    public void addAll(MultiValueMap<String, String> other) {
        super.addAll(other);
    }

    @Override
    public void set(String key, String value) {
        super.set(key, value);
    }

    @Override
    public void setAll(Map<String, String> map) {
        super.setAll(map);
    }

    @Override
    public Map<String, String> toSingleValueMap() {
        return super.toSingleValueMap();
    }

    // ========================================================================
    // Map methods
    // ========================================================================

    @Override
    public int size() {
        return super.size();
    }

    @Override
    public boolean isEmpty() {
        return super.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return super.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return super.containsValue(value);
    }

    @Override
    public List<String> get(Object key) {
        return super.get(key);
    }

    @Override
    public List<String> put(String key, List<String> value) {
        return super.put(key, value);
    }

    @Override
    public List<String> remove(Object key) {
        return super.remove(key);
    }

    @Override
    public void putAll(Map<? extends String, ? extends List<String>> map) {
        super.putAll(map);
    }

    @Override
    public void clear() {
        super.clear();
    }

    @Override
    public Set<String> keySet() {
        return super.keySet();
    }

    @Override
    public Collection<List<String>> values() {
        return super.values();
    }

    @Override
    public Set<Entry<String, List<String>>> entrySet() {
        return super.entrySet();
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

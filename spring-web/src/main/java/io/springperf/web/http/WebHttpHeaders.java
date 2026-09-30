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
 * {@link HttpHeaders} 子类，在保留 Spring 原生行为的基础上做两处优化：
 * <ol>
 * <li>缓存 {@link #getContentType()} 的解析结果，避免重复 {@link MediaType#parseMediaType}；</li>
 * <li>当底层存储是可写的 {@link NettyHttpHeadersAdapter} 时，Content-Type 的读写走
 * {@code HttpHeaderNames} 常量名直通 Netty，省掉每次按 String 名查找与 {@code AsciiString} 名字重算哈希。</li>
 * </ol>
 * <p>
 * 本类同时实现 {@link MultiValueMap}，与 Spring 6 的 {@code HttpHeaders} 一致：父类已经实现了该接口，
 * 这里的覆写只是为上面两处优化提供入口，其余一律委派 {@code super}。
 * </p>
 */
@SuppressWarnings("deprecation")
public class WebHttpHeaders extends HttpHeaders implements MultiValueMap<String, String> {

    /** 缓存 {@link #getContentType()} 的解析结果，避免重复 {@link MediaType#parseMediaType} */
    private MediaType cachedContentType;

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

    private static final MediaType NOT_SET = new MediaType("application", "x-not-set");

    public WebHttpHeaders() {
        this.cachedContentType = NOT_SET;
        this.rawHeaders = null;
    }

    /**
     * 用已存在的 {@code MultiValueMap} 视图构造，持有引用而非拷贝（零拷贝）。
     * <p>
     * Spring 的 {@code HttpHeaders(MultiValueMap)} 为引用持有（已反编译验证 {@code putfield headers} 无拷贝循环）。
     * 传入 {@link NettyHttpHeadersAdapter} 即可获得 Netty headers 的只读零拷贝视图。
     * </p>
     */
    public WebHttpHeaders(MultiValueMap<String, String> headers) {
        super(headers);
        this.cachedContentType = NOT_SET;
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
    // 缓存解析结果
    // ========================================================================

    @Override
    public MediaType getContentType() {
        if (cachedContentType == NOT_SET) {
            cachedContentType = rawHeaders != null ? parseContentType(rawHeaders) : super.getContentType();
            if (cachedContentType == null) {
                cachedContentType = NOT_SET;
            }
        }
        return cachedContentType == NOT_SET ? null : cachedContentType;
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
        // 清空缓存，下次 getContentType() 重新解析
        cachedContentType = NOT_SET;
    }
}

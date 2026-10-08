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
 * {@link HttpHeaders} 子类，在 Spring Framework 7（Spring Boot 4.x）下实现 {@link MultiValueMap}。
 * <p>
 * Spring 7 的 {@code HttpHeaders} 不再实现 {@code MultiValueMap}，也不再有 {@code containsKey} / {@code containsValue} /
 * {@code keySet} / {@code values} / {@code entrySet} —— 这些方法改由 {@link HttpHeaders#asMultiValueMap()} 返回的内部
 * {@code MultiValueMap} 视图暴露。本类在构造期缓存该视图 （零拷贝，指向父类内部的 headers 字段），把 Spring 7 缺失的那批方法委派过去；父类已有的方法 （{@code size} /
 * {@code isEmpty} / {@code get} / {@code put} / {@code remove} / {@code putAll} / {@code clear} 等）直接调 {@code super}。
 * </p>
 * <p>
 * <b>分支说明</b>：本分支（4.1.x）专用 Spring Boot 4.1，不与 3.5.x 共用代码，因此这里<b>没有</b> 任何运行时版本探测。早期 master 上的实现为同时兼容 Spring 6/7，用
 * {@code static final boolean} + 8 个 {@code MethodHandle}（{@code findSpecial} 调父类默认实现）+ {@code invokeWithArguments}
 * 在类加载期选分支；随「master 收敛为纯 3.5.x、4.x 适配移入本分支」，那套机制在本分支已无必要 ——直接写 Spring 7 的形态即可。与 {@code 2.7.x} 分支对 Spring 5.3 的处理方式一致。
 * </p>
 * <p>
 * <b>性能</b>：调用点此前为绕开类型不匹配而写 {@code toSingleValueMap().keySet()}（O(n) 复制）， 现在可直接用 {@code keySet()}；也去掉了
 * {@code RequestHeaderResolverProvider} 里的反射兼容代码。
 * </p>
 * <p>
 * <b>不缓存 {@link #getContentType()} 的解析结果</b>（曾用 {@code cachedContentType} 字段）：该缓存只能由 {@link #setContentType(MediaType)}
 * 重置，而本类另有多个覆写方法（{@code set} / {@code add} / {@code remove} / {@code put} / {@code putAll} / {@code clear} …）会改动 header
 * 却<b>不</b>重置它 —— 业务经 {@code getHeaders().set("Content-Type", v)} 写入后会读到过期值，且该失效无任何报错，属静默错误。 去掉后的代价实测为服务端分配 +27
 * B/op（json 场景，+0.5%），相对吞吐测量噪声可忽略。
 * </p>
 */
public class WebHttpHeaders extends HttpHeaders implements MultiValueMap<String, String> {

    /**
     * 父类内部 headers 的 {@code MultiValueMap} 视图（{@link HttpHeaders#asMultiValueMap()} 返回值）。
     * <p>
     * Spring 7 的 {@code HttpHeaders} 把 {@code containsKey} / {@code keySet} / {@code entrySet} 等 「Map
     * 语义」方法移到了这个视图上，父类自身不再暴露，故本类缓存它并在对应方法里委派。
     * </p>
     */
    private final MultiValueMap<String, String> delegateMap;

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
        this.delegateMap = asMultiValueMap();
        this.rawHeaders = null;
    }

    /**
     * 用已存在的 {@code MultiValueMap} 视图构造，持有引用而非拷贝（零拷贝）。
     * <p>
     * Spring 7 的 {@code HttpHeaders(MultiValueMap)} 为引用持有（已反编译验证 {@code putfield headers} 无拷贝循环）。传入
     * {@link NettyHttpHeadersAdapter} 即可获得 Netty headers 的只读零拷贝视图。
     * </p>
     */
    public WebHttpHeaders(MultiValueMap<String, String> headers) {
        super(headers);
        this.delegateMap = asMultiValueMap();
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

    /** Spring 7 的重载是 {@code addAll(HttpHeaders)}；父类没有 {@code addAll(MultiValueMap)}。 */
    @Override
    public void addAll(MultiValueMap<String, String> other) {
        delegateMap.addAll(other);
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

    /** 父类无此方法（Spring 7 移到 {@code asMultiValueMap()} 视图上），故委派 {@link #delegateMap}。 */
    @Override
    public boolean containsKey(Object key) {
        return delegateMap.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return delegateMap.containsValue(value);
    }

    @Override
    public List<String> get(Object key) {
        return key instanceof String ? super.get((String) key) : delegateMap.get(key);
    }

    @Override
    public List<String> put(String key, List<String> value) {
        return super.put(key, value);
    }

    @Override
    public List<String> remove(Object key) {
        return key instanceof String ? super.remove((String) key) : delegateMap.remove(key);
    }

    @Override
    public void putAll(Map<? extends String, ? extends List<String>> map) {
        super.putAll(map);
    }

    /**
     * 把 {@code source} 的条目<b>追加</b>到 {@code target}（保留同名头已有值）。
     * <p>
     * <b>为什么需要它</b>：调用方若写 {@code target.putAll(sourceHeaders)}，javac 会选中 {@code HttpHeaders} 的
     * {@code putAll(HttpHeaders)} 重载（内部是 forEach+put、语义为整体替换）， 与 {@code Map.putAll} 语义不同，还可能因 {@code put} 落到只读视图而抛
     * {@code UnsupportedOperationException}。这里改用 {@link HttpHeaders#headerSet()}（签名稳定） 逐条 {@link HttpHeaders#add} ——
     * 语义明确，也避开重载解析陷阱。
     * </p>
     * <p>
     * 声明为 static 且形参用 {@code HttpHeaders}：调用方拿到的常是声明类型 {@code HttpHeaders} 的 引用（如
     * {@code WebServerHttpResponse.getHeaders()}），静态方法无需强转即可复用。
     * </p>
     */
    public static void addAllHeaders(HttpHeaders target, HttpHeaders source) {
        if (target == null || source == null) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : source.headerSet()) {
            List<String> values = entry.getValue();
            if (values != null) {
                for (String value : values) {
                    target.add(entry.getKey(), value);
                }
            }
        }
    }

    @Override
    public void clear() {
        super.clear();
    }

    /** 父类无此方法（Spring 7 移到 {@code asMultiValueMap()} 视图上），故委派 {@link #delegateMap}。 */
    @Override
    public Set<String> keySet() {
        return delegateMap.keySet();
    }

    @Override
    public Collection<List<String>> values() {
        return delegateMap.values();
    }

    @Override
    public Set<Entry<String, List<String>>> entrySet() {
        return delegateMap.entrySet();
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

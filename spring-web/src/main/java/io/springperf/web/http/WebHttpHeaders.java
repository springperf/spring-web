package io.springperf.web.http;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;

import io.netty.handler.codec.http.HttpHeaderNames;

/**
 * Cross-version compatible {@link HttpHeaders} that implements {@link MultiValueMap}.
 * <p>
 * <b>Spring 6.x (SB 3.x):</b> {@code HttpHeaders} already implements {@code MultiValueMap<String, String>}, so this
 * class inherits the interface naturally. All {@code super.*()} calls use the parent's built-in behavior.
 * <p>
 * <b>Spring 7.x (SB 4.x):</b> {@code HttpHeaders} no longer implements {@code MultiValueMap}. At class load time we
 * resolve the package-private {@code asMultiValueMap()} method via {@link MethodHandle} and cache the returned delegate
 * reference (which IS the parent's internal {@code MultiValueMap<String, String> headers} field). All
 * {@code MultiValueMap} and {@code Map} methods delegate to this reference.
 * <p>
 * The JIT eliminates the version branch in every method because {@link #HEADERS_IS_MULTI_VALUE_MAP} is
 * {@code static final boolean}.
 * <p>
 * <b>Performance:</b> Eliminates O(n) copies at call sites that previously used {@code toSingleValueMap().keySet()} to
 * work around the type mismatch, and removes the reflective compatibility code in
 * {@code RequestHeaderResolverProvider}.
 * <p>
 * Also caches {@link #getContentType()} result to avoid repeated {@code MediaType.parseMediaType()} calls.
 * </p>
 */
@SuppressWarnings("deprecation")
public class WebHttpHeaders extends HttpHeaders implements MultiValueMap<String, String> {

    private static final boolean HEADERS_IS_MULTI_VALUE_MAP;
    private static final MethodHandle AS_MULTI_VALUE_MAP;

    /**
     * 以下 5 个方法句柄仅在 Spring 6.x 使用：{@code containsKey} / {@code containsValue} / {@code keySet} /
     * {@code values} / {@code entrySet} 在 Spring 6 的 {@code HttpHeaders} 上是**继承自 {@code MultiValueMap} 的
     * 默认实现**，在 Spring 7 里被整体移除（改由 {@code asMultiValueMap()} 暴露）。
     * <p>
     * 编译期无法写 {@code super.containsKey(...)} —— javac 解析 {@code super.} 时不看运行时分支，
     * Spring 7 下父类没有该方法即报「找不到符号」。故 6.x 分支改走 {@link MethodHandles.Lookup#findSpecial}
     * 直接调父类实现：语义与原 {@code super.xxx()} 完全一致（同样是父类方法体，不经过本类覆写、不会递归）。
     * </p>
     * <p>
     * Spring 7 下这些句柄保持 {@code null} 且永不被调用（分支由 {@link #HEADERS_IS_MULTI_VALUE_MAP} 静态常量决定，
     * JIT 会消除死分支）。
     * </p>
     */
    private static final MethodHandle SUPER_CONTAINS_KEY;
    private static final MethodHandle SUPER_CONTAINS_VALUE;
    private static final MethodHandle SUPER_KEY_SET;
    private static final MethodHandle SUPER_VALUES;
    private static final MethodHandle SUPER_ENTRY_SET;
    /** Spring 6 的 {@code addAll(MultiValueMap)}；Spring 7 该重载改成了 {@code addAll(HttpHeaders)}。 */
    private static final MethodHandle SUPER_ADD_ALL_MAP;
    /**
     * Spring 6 的 {@code get(Object)} / {@code remove(Object)}：Spring 7 把参数从 {@code Object} **收窄为
     * {@code String}**，因此 6.x 分支不能再写 {@code super.get(key)}（key 是 Object）。
     */
    private static final MethodHandle SUPER_GET_OBJECT;
    private static final MethodHandle SUPER_REMOVE_OBJECT;

    static {
        boolean isMap = false;
        MethodHandle mh = null;
        try {
            isMap = MultiValueMap.class.isAssignableFrom(HttpHeaders.class);
        } catch (Exception ignored) {
            // Should not happen — HttpHeaders exists in all supported versions
        }
        HEADERS_IS_MULTI_VALUE_MAP = isMap;
        if (!isMap) {
            try {
                mh = MethodHandles.lookup().unreflect(HttpHeaders.class.getDeclaredMethod("asMultiValueMap"));
            } catch (Exception ignored) {
                // Should not happen — asMultiValueMap() exists in Spring 7.x
            }
        }
        AS_MULTI_VALUE_MAP = mh;

        // Spring 6 专属：为上面 5 个「被 Spring 7 移除」的方法解析父类句柄（findSpecial = super 调用语义）
        MethodHandle containsKey = null;
        MethodHandle containsValue = null;
        MethodHandle keySet = null;
        MethodHandle values = null;
        MethodHandle entrySet = null;
        MethodHandle addAllMap = null;
        MethodHandle getObject = null;
        MethodHandle removeObject = null;
        if (isMap) {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Class<?> p = HttpHeaders.class;
            Class<?> self = WebHttpHeaders.class;
            try {
                containsKey = lookup.findSpecial(p, "containsKey",
                        java.lang.invoke.MethodType.methodType(boolean.class, Object.class), self);
                containsValue = lookup.findSpecial(p, "containsValue",
                        java.lang.invoke.MethodType.methodType(boolean.class, Object.class), self);
                keySet = lookup.findSpecial(p, "keySet",
                        java.lang.invoke.MethodType.methodType(Set.class), self);
                values = lookup.findSpecial(p, "values",
                        java.lang.invoke.MethodType.methodType(Collection.class), self);
                entrySet = lookup.findSpecial(p, "entrySet",
                        java.lang.invoke.MethodType.methodType(Set.class), self);
                addAllMap = lookup.findSpecial(p, "addAll",
                        java.lang.invoke.MethodType.methodType(void.class, MultiValueMap.class), self);
                getObject = lookup.findSpecial(p, "get",
                        java.lang.invoke.MethodType.methodType(List.class, Object.class), self);
                removeObject = lookup.findSpecial(p, "remove",
                        java.lang.invoke.MethodType.methodType(List.class, Object.class), self);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to resolve Spring 6 HttpHeaders super-method handles — "
                                + "the inherited MultiValueMap defaults are missing",
                        e);
            }
        }
        SUPER_CONTAINS_KEY = containsKey;
        SUPER_CONTAINS_VALUE = containsValue;
        SUPER_KEY_SET = keySet;
        SUPER_VALUES = values;
        SUPER_ENTRY_SET = entrySet;
        SUPER_ADD_ALL_MAP = addAllMap;
        SUPER_GET_OBJECT = getObject;
        SUPER_REMOVE_OBJECT = removeObject;
    }

    /** 调用 6.x 的父类实现（语义等同原 {@code super.xxx()}）。 */
    private static Object invokeSuper(MethodHandle handle, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Failed to invoke HttpHeaders super-method", e);
        }
    }

    /**
     * Cached delegate reference (Spring 7.x only). Points to the parent's internal
     * {@code MultiValueMap<String, String> headers} field. {@code null} on Spring 6.x where {@code this} IS the
     * delegate.
     */
    private final MultiValueMap<String, String> delegateMap;

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
        this.delegateMap = resolveDelegateForVersion();
        this.cachedContentType = NOT_SET;
        this.rawHeaders = null;
    }

    /**
     * 用已存在的 {@code MultiValueMap} 视图构造，持有引用而非拷贝（零拷贝）。
     * <p>
     * 5.3/6.x/7.x 的 {@code HttpHeaders(MultiValueMap)} 均为引用持有 （已反编译验证 {@code putfield headers} 无拷贝循环）。传入
     * {@link NettyHttpHeadersAdapter} 即可获得 Netty headers 的只读零拷贝视图。
     * </p>
     */
    public WebHttpHeaders(MultiValueMap<String, String> headers) {
        super(headers);
        this.delegateMap = resolveDelegateForVersion();
        this.cachedContentType = NOT_SET;
        this.rawHeaders = (headers instanceof NettyHttpHeadersAdapter)
                ? ((NettyHttpHeadersAdapter) headers).rawHeadersIfWritable()
                : null;
    }

    private MultiValueMap<String, String> resolveDelegateForVersion() {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            return null;
        }
        return resolveDelegate();
    }

    private MultiValueMap<String, String> resolveDelegate() {
        if (AS_MULTI_VALUE_MAP == null) {
            throw new IllegalStateException("Cannot resolve HttpHeaders.asMultiValueMap() — this should not happen");
        }
        try {
            return (MultiValueMap<String, String>) AS_MULTI_VALUE_MAP.invoke(this);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke asMultiValueMap()", e);
        }
    }

    // ========================================================================
    // MultiValueMap methods
    // ========================================================================

    @Override
    public String getFirst(String key) {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            return super.getFirst(key);
        }
        return delegateMap.getFirst(key);
    }

    @Override
    public void add(String key, String value) {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            super.add(key, value);
        } else {
            delegateMap.add(key, value);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void addAll(String key, List<? extends String> values) {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            super.addAll(key, values);
        } else {
            delegateMap.addAll(key, (List<String>) values);
        }
    }

    @Override
    public void addAll(MultiValueMap<String, String> other) {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            invokeSuper(SUPER_ADD_ALL_MAP, this, other);
        } else {
            delegateMap.addAll(other);
        }
    }

    @Override
    public void set(String key, String value) {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            super.set(key, value);
        } else {
            delegateMap.set(key, value);
        }
    }

    @Override
    public void setAll(Map<String, String> map) {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            super.setAll(map);
        } else {
            delegateMap.setAll(map);
        }
    }

    @Override
    public Map<String, String> toSingleValueMap() {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            return super.toSingleValueMap();
        }
        return delegateMap.toSingleValueMap();
    }

    // ========================================================================
    // Map methods
    // ========================================================================

    @Override
    public int size() {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            return super.size();
        }
        return delegateMap.size();
    }

    @Override
    public boolean isEmpty() {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            return super.isEmpty();
        }
        return delegateMap.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            return (boolean) invokeSuper(SUPER_CONTAINS_KEY, this, key);
        }
        return delegateMap.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            return (boolean) invokeSuper(SUPER_CONTAINS_VALUE, this, value);
        }
        return delegateMap.containsValue(value);
    }

    @Override
    public List<String> get(Object key) {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            return (List<String>) invokeSuper(SUPER_GET_OBJECT, this, key);
        }
        return delegateMap.get(key);
    }

    @Override
    public List<String> put(String key, List<String> value) {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            return super.put(key, value);
        }
        return delegateMap.put(key, value);
    }

    @Override
    public List<String> remove(Object key) {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            return (List<String>) invokeSuper(SUPER_REMOVE_OBJECT, this, key);
        }
        return delegateMap.remove(key);
    }

    @Override
    public void putAll(Map<? extends String, ? extends List<String>> map) {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            super.putAll(map);
        } else {
            delegateMap.putAll(map);
        }
    }

    /**
     * 把 {@code source} 的条目<b>追加</b>到 {@code target}（保留同名头已有值）。
     * <p>
     * <b>为什么需要它</b>：调用方若写 {@code target.putAll(sourceHeaders)}，在 Spring 7 下 javac 会选中
     * {@code HttpHeaders} 新增的 {@code putAll(HttpHeaders)} 重载（内部是 forEach+put、语义为整体替换），
     * 与 Spring 6 的 {@code Map.putAll} 语义不同，还可能因 {@code put} 落到只读视图而抛
     * {@code UnsupportedOperationException}。这里改用 {@link HttpHeaders#headerSet()}（两版本签名一致）
     * 逐条 {@link HttpHeaders#add} —— 语义明确、跨版本一致，也避开重载解析陷阱。
     * </p>
     * <p>
     * 声明为 static 且形参用 {@code HttpHeaders}：调用方拿到的常是声明类型 {@code HttpHeaders} 的
     * 引用（如 {@code WebServerHttpResponse.getHeaders()}），静态方法无需强转即可复用。
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
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            super.clear();
        } else {
            delegateMap.clear();
        }
    }

    @Override
    public Set<String> keySet() {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            return (Set<String>) invokeSuper(SUPER_KEY_SET, this);
        }
        return delegateMap.keySet();
    }

    @Override
    public Collection<List<String>> values() {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            return (Collection<List<String>>) invokeSuper(SUPER_VALUES, this);
        }
        return delegateMap.values();
    }

    @Override
    public Set<Entry<String, List<String>>> entrySet() {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            return (Set<Entry<String, List<String>>>) invokeSuper(SUPER_ENTRY_SET, this);
        }
        return delegateMap.entrySet();
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

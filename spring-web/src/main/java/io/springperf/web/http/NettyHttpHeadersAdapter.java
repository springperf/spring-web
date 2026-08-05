package io.springperf.web.http;

import io.netty.handler.codec.http.HttpHeaders;
import org.springframework.util.MultiValueMap;

import java.util.*;

/**
 * 基于 Netty {@link HttpHeaders} 的零拷贝 {@link MultiValueMap} 视图，读写按需可选。
 *
 * <p><b>请求侧（可写）：</b>请求头视图直接委托 Netty headers，写操作穿透到
 * Netty 请求对象（对齐 Spring {@code ServerHttpRequest.getHeaders()} 的可写契约，
 * 支持 WebFilter 改写请求头，如 Trace ID 注入、X-Forwarded-* 归一化）。
 * 相比旧实现（每请求首次访问把 Netty headers 全量拷入 LinkedMultiValueMap），
 * 免去 O(n) 拷贝与逐条 add 分配，并保留 Netty 的大小写不敏感解析——旧拷贝为
 * 大小写敏感的 LinkedMultiValueMap，导致小写 key 读取永远 miss，缓存 key 恒为
 * 通配类型（见 HttpBodyCodecRegistry.normalizeAcceptKey）。
 *
 * <p><b>响应侧（可写）：</b>用 {@link #NettyHttpHeadersAdapter(HttpHeaders, boolean)}
 * 传入 writable=true，所有写操作委托 Netty headers。配合
 * {@link NettyServerHttpResponse#nettyHeaders} 共享同一 {@code DefaultHttpHeaders}
 * 实例传给 Netty 响应对象，消除 commit 时框架 map → Netty headers 的拷贝循环，
 * 并把两套 header 存储压成一套。
 *
 * <p>仅依赖 Netty API，与 Spring 版本无关。Spring 自带 {@code Netty4HeadersAdapter}
 * 仅 6.1+ 存在，直接引用会破坏 5.3/SB 2.7 编译，故自研以跨 5.3/6.x/7.x。
 */
public class NettyHttpHeadersAdapter implements MultiValueMap<String, String> {

    private final HttpHeaders headers;
    private final boolean writable;

    /** 只读视图：写操作抛 {@link UnsupportedOperationException} */
    public NettyHttpHeadersAdapter(HttpHeaders headers) {
        this(headers, false);
    }

    /** 可写视图：写操作委托底层 Netty headers */
    public NettyHttpHeadersAdapter(HttpHeaders headers, boolean writable) {
        this.headers = headers;
        this.writable = writable;
    }

    // ==================== 读 ====================

    @Override
    public String getFirst(String key) {
        return headers.get(key);
    }

    @Override
    public int size() {
        return headers.size();
    }

    @Override
    public boolean isEmpty() {
        return headers.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof String && headers.contains((String) key);
    }

    @Override
    public boolean containsValue(Object value) {
        if (!(value instanceof String)) {
            return false;
        }
        String target = (String) value;
        for (Map.Entry<String, String> entry : headers) {
            if (target.equals(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> get(Object key) {
        if (!(key instanceof String)) {
            return null;
        }
        String name = (String) key;
        if (!headers.contains(name)) {
            return null;
        }
        return headers.getAll(name);
    }

    @Override
    public Set<String> keySet() {
        return headers.names();
    }

    @Override
    public Collection<List<String>> values() {
        List<List<String>> result = new ArrayList<>();
        for (String name : headers.names()) {
            result.add(headers.getAll(name));
        }
        return result;
    }

    @Override
    public Set<Entry<String, List<String>>> entrySet() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String name : headers.names()) {
            map.put(name, headers.getAll(name));
        }
        return map.entrySet();
    }

    @Override
    public Map<String, String> toSingleValueMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String name : headers.names()) {
            map.put(name, headers.get(name));
        }
        return map;
    }

    // ==================== 写 ====================

    private void checkWritable() {
        if (!writable) {
            throw new UnsupportedOperationException("Headers view is read-only");
        }
    }

    @Override
    public void add(String key, String value) {
        checkWritable();
        headers.add(key, value);
    }

    @Override
    public void addAll(String key, List<? extends String> values) {
        checkWritable();
        headers.add(key, values);
    }

    @Override
    public void addAll(MultiValueMap<String, String> values) {
        checkWritable();
        for (Entry<String, List<String>> entry : values.entrySet()) {
            headers.add(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void set(String key, String value) {
        checkWritable();
        headers.set(key, value);
    }

    @Override
    public void setAll(Map<String, String> values) {
        checkWritable();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            headers.set(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public List<String> put(String key, List<String> value) {
        checkWritable();
        List<String> previous = headers.contains(key) ? headers.getAll(key) : null;
        headers.set(key, value);
        return previous;
    }

    @Override
    public List<String> remove(Object key) {
        checkWritable();
        List<String> previous = null;
        if (key instanceof String && headers.contains((String) key)) {
            previous = headers.getAll((String) key);
            headers.remove((String) key);
        }
        return previous;
    }

    @Override
    public void putAll(Map<? extends String, ? extends List<String>> map) {
        checkWritable();
        for (Map.Entry<? extends String, ? extends List<String>> entry : map.entrySet()) {
            headers.set(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void clear() {
        checkWritable();
        headers.clear();
    }
}

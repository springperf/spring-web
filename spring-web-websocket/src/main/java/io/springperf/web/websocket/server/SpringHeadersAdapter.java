package io.springperf.web.websocket.server;

import org.springframework.http.HttpHeaders;

import java.util.*;

/**
 * 将 Netty {@link io.netty.handler.codec.http.HttpHeaders} 适配为 Spring {@link HttpHeaders}。
 * <p>
 * <b>跨版本注意</b>：Spring 7 的 {@code HttpHeaders} 不再是 {@code MultiValueMap}，下列 8 个 Map 方法
 * （{@code get}/{@code keySet}/{@code entrySet}/{@code containsKey}/{@code containsValue}/{@code put}/
 * {@code remove}/{@code values}）在父类上<b>已不存在</b>，故不能标 {@code @Override}（会报「方法不会覆盖 或实现超类型的方法」）。它们仍作为本类的自有 API
 * 保留，供调用方按需使用。
 * </p>
 *
 * @author huangcanda
 *
 * @since 1.0.4
 */
public class SpringHeadersAdapter extends HttpHeaders {

    private final Map<String, List<String>> headers;

    public SpringHeadersAdapter(io.netty.handler.codec.http.HttpHeaders nettyHeaders) {
        this.headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : nettyHeaders) {
            this.headers.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
        }
    }

    public List<String> get(Object key) {
        return headers.get(key);
    }

    @Override
    public String getFirst(String key) {
        List<String> values = headers.get(key);
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }

    public Set<String> keySet() {
        return headers.keySet();
    }

    public Set<Map.Entry<String, List<String>>> entrySet() {
        return headers.entrySet();
    }

    @Override
    public int size() {
        return headers.size();
    }

    @Override
    public boolean isEmpty() {
        return headers.isEmpty();
    }

    public boolean containsKey(Object key) {
        return headers.containsKey(key);
    }

    public boolean containsValue(Object value) {
        return headers.containsValue(value);
    }

    public List<String> put(String key, List<String> value) {
        return headers.put(key, value);
    }

    public List<String> remove(Object key) {
        return headers.remove(key);
    }

    @Override
    public void clear() {
        headers.clear();
    }

    public Collection<List<String>> values() {
        return headers.values();
    }

    @Override
    public String toString() {
        return headers.toString();
    }
}

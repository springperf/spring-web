package io.springperf.web.websocket.server;

import org.springframework.http.HttpHeaders;

import java.util.*;

/**
 * 将 Netty {@link io.netty.handler.codec.http.HttpHeaders} 适配为 Spring {@link HttpHeaders}。
 *
 * @author huangcanda
 * @since 1.0.4
 */
public class SpringHeadersAdapter extends HttpHeaders {

    private final Map<String, List<String>> headers;

    public SpringHeadersAdapter(io.netty.handler.codec.http.HttpHeaders nettyHeaders) {
        this.headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : nettyHeaders) {
            this.headers.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                    .add(entry.getValue());
        }
    }

    @Override
    public List<String> get(Object key) {
        return headers.get(key);
    }

    @Override
    public String getFirst(String key) {
        List<String> values = headers.get(key);
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }

    @Override
    public Set<String> keySet() {
        return headers.keySet();
    }

    @Override
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

    @Override
    public boolean containsKey(Object key) {
        return headers.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return headers.containsValue(value);
    }

    @Override
    public List<String> put(String key, List<String> value) {
        return headers.put(key, value);
    }

    @Override
    public List<String> remove(Object key) {
        return headers.remove(key);
    }

    @Override
    public void clear() {
        headers.clear();
    }

    @Override
    public Collection<List<String>> values() {
        return headers.values();
    }

    @Override
    public String toString() {
        return headers.toString();
    }
}

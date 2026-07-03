package io.springperf.web.http;

import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-version compatible {@link HttpHeaders} that implements {@link MultiValueMap}.
 *
 * <p><b>Spring 6.x (SB 3.x):</b> {@code HttpHeaders} already implements
 * {@code MultiValueMap<String, String>}, so this class inherits the interface
 * naturally. All {@code super.*()} calls use the parent's built-in behavior.
 *
 * <p><b>Spring 7.x (SB 4.x):</b> {@code HttpHeaders} no longer implements
 * {@code MultiValueMap}. At class load time we resolve the package-private
 * {@code asMultiValueMap()} method via {@link MethodHandle} and cache the
 * returned delegate reference (which IS the parent's internal
 * {@code MultiValueMap<String, String> headers} field). All {@code MultiValueMap}
 * and {@code Map} methods delegate to this reference.
 *
 * <p>The JIT eliminates the version branch in every method because
 * {@link #HEADERS_IS_MULTI_VALUE_MAP} is {@code static final boolean}.
 *
 * <p><b>Performance:</b> Eliminates O(n) copies at call sites that previously
 * used {@code toSingleValueMap().keySet()} to work around the type mismatch,
 * and removes the reflective compatibility code in {@code RequestHeaderResolverProvider}.
 */
@SuppressWarnings("deprecation")
public class WebHttpHeaders extends HttpHeaders implements MultiValueMap<String, String> {

    private static final boolean HEADERS_IS_MULTI_VALUE_MAP;
    private static final MethodHandle AS_MULTI_VALUE_MAP;

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
                mh = MethodHandles.lookup().unreflect(
                        HttpHeaders.class.getDeclaredMethod("asMultiValueMap"));
            } catch (Exception ignored) {
                // Should not happen — asMultiValueMap() exists in Spring 7.x
            }
        }
        AS_MULTI_VALUE_MAP = mh;
    }

    /**
     * Cached delegate reference (Spring 7.x only).
     * Points to the parent's internal {@code MultiValueMap<String, String> headers} field.
     * {@code null} on Spring 6.x where {@code this} IS the delegate.
     */
    private final MultiValueMap<String, String> delegateMap;

    public WebHttpHeaders() {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            this.delegateMap = null;
        } else {
            this.delegateMap = resolveDelegate();
        }
    }

    private MultiValueMap<String, String> resolveDelegate() {
        if (AS_MULTI_VALUE_MAP == null) {
            throw new IllegalStateException(
                    "Cannot resolve HttpHeaders.asMultiValueMap() — this should not happen");
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
            super.addAll(other);
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
            return super.containsKey(key);
        }
        return delegateMap.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            return super.containsValue(value);
        }
        return delegateMap.containsValue(value);
    }

    @Override
    public List<String> get(Object key) {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            return super.get(key);
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
            return super.remove(key);
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
            return super.keySet();
        }
        return delegateMap.keySet();
    }

    @Override
    public Collection<List<String>> values() {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            return super.values();
        }
        return delegateMap.values();
    }

    @Override
    public Set<Entry<String, List<String>>> entrySet() {
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            return super.entrySet();
        }
        return delegateMap.entrySet();
    }
}

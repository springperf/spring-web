package io.springperf.web.core.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Adapter for {@link ResponseStatusException#getResponseHeaders()} which was renamed
 * to {@code getHeaders()} in Spring Framework 7.0+ (SB 4.x).
 * <p>Also bridges the {@code MultiValueMap} type gap: on SB 4.x, {@code HttpHeaders}
 * no longer implements {@code MultiValueMap}, so we unwrap the internal map via
 * {@code asMultiValueMap()} to avoid O(n) copies at call sites.
 * <p>Uses reflection + {@link MethodHandle} to maintain cross-version compatibility.
 */
public class ResponseStatusExceptionAdapter {

    private static final Method HEADERS_GETTER;

    private static final boolean HEADERS_IS_MULTI_VALUE_MAP;
    private static final MethodHandle AS_MULTI_VALUE_MAP;

    static {
        // Resolve getHeaders() vs getResponseHeaders()
        Method m = null;
        try {
            m = ResponseStatusException.class.getMethod("getHeaders");
        } catch (NoSuchMethodException e) {
            try {
                m = ResponseStatusException.class.getMethod("getResponseHeaders");
            } catch (NoSuchMethodException ex) {
                // Should not happen — at least one exists in any supported Spring version
            }
        }
        HEADERS_GETTER = m;

        // Resolve asMultiValueMap() for SB 4.x
        boolean isMap = false;
        MethodHandle mh = null;
        try {
            isMap = MultiValueMap.class.isAssignableFrom(HttpHeaders.class);
        } catch (Exception ignored) {
        }
        HEADERS_IS_MULTI_VALUE_MAP = isMap;
        if (!isMap) {
            try {
                mh = MethodHandles.lookup().unreflect(
                        HttpHeaders.class.getDeclaredMethod("asMultiValueMap"));
            } catch (Exception ignored) {
            }
        }
        AS_MULTI_VALUE_MAP = mh;
    }

    /**
     * Return the headers from a {@link ResponseStatusException} as a
     * {@link MultiValueMap}, regardless of Spring version.
     */
    @SuppressWarnings("unchecked")
    public static MultiValueMap<String, String> getHeaders(ResponseStatusException ex) {
        if (HEADERS_GETTER == null) {
            return new HttpHeaders();
        }
        HttpHeaders headers;
        try {
            headers = (HttpHeaders) HEADERS_GETTER.invoke(ex);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        if (HEADERS_IS_MULTI_VALUE_MAP) {
            return (MultiValueMap<String, String>) headers;
        }
        // SB 4.x: unwrap internal MultiValueMap via asMultiValueMap()
        try {
            return (MultiValueMap<String, String>) AS_MULTI_VALUE_MAP.invoke(headers);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}

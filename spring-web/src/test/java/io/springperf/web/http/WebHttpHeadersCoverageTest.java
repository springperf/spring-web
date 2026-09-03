package io.springperf.web.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebHttpHeadersCoverageTest {

    private static final String FLAG_FIELD = "HEADERS_IS_MULTI_VALUE_MAP";
    private static final String HANDLE_FIELD = "AS_MULTI_VALUE_MAP";

    private boolean originalFlag;
    private MethodHandle originalHandle;

    @BeforeEach
    void captureStatics() throws Exception {
        originalFlag = field(FLAG_FIELD).getBoolean(null);
        originalHandle = (MethodHandle) field(HANDLE_FIELD).get(null);
    }

    @AfterEach
    void restoreStatics() {
        setFlag(originalFlag);
        setHandle(originalHandle);
    }

    private static final sun.misc.Unsafe UNSAFE = unsafe();

    private static sun.misc.Unsafe unsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Field field(String name) throws Exception {
        Field f = WebHttpHeaders.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static void setFlag(boolean value) {
        try {
            Field f = field(FLAG_FIELD);
            UNSAFE.putBoolean(UNSAFE.staticFieldBase(f), UNSAFE.staticFieldOffset(f), value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setHandle(MethodHandle handle) {
        try {
            Field f = field(HANDLE_FIELD);
            UNSAFE.putObject(UNSAFE.staticFieldBase(f), UNSAFE.staticFieldOffset(f), handle);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MethodHandle asMvmHandle() throws Exception {
        return MethodHandles.lookup()
                .findStatic(WebHttpHeadersCoverageTest.class, "asMvm",
                        MethodType.methodType(Object.class, Object.class));
    }

    private static MethodHandle failingHandle() throws Exception {
        return MethodHandles.lookup()
                .findStatic(WebHttpHeadersCoverageTest.class, "fail",
                        MethodType.methodType(Object.class, Object.class));
    }

    static Object asMvm(Object self) throws Exception {
        Field f = HttpHeaders.class.getDeclaredField("headers");
        f.setAccessible(true);
        return f.get(self);
    }

    static Object fail(Object self) {
        throw new IllegalStateException("boom");
    }

    @Test
    void addAll_twoArg_appendsMultipleValues() {
        WebHttpHeaders headers = new WebHttpHeaders();
        headers.addAll("X-Foo", Arrays.asList("a", "b"));
        assertEquals("a", headers.getFirst("X-Foo"));
        assertEquals(2, headers.get("X-Foo").size());
    }

    @Test
    void constructor_withProvidedMap_holdsReference() {
        MultiValueMap<String, String> store = new LinkedMultiValueMap<>();
        WebHttpHeaders headers = new WebHttpHeaders(store);
        store.set("X-K", "v");
        assertEquals("v", headers.getFirst("X-K"));
    }

    @Test
    void delegateMode_supportsFullMultiValueMapSurface() throws Exception {
        setFlag(false);
        setHandle(asMvmHandle());
        try {
            WebHttpHeaders h = new WebHttpHeaders();
            assertTrue(h.isEmpty());
            h.add("h1", "v1");
            assertEquals("v1", h.getFirst("h1"));
            h.addAll("h2", Arrays.asList("a", "b"));
            assertEquals(Arrays.asList("a", "b"), h.get("h2"));
            h.set("h3", "v3");
            assertEquals("v3", h.getFirst("h3"));
            MultiValueMap<String, String> other = new LinkedMultiValueMap<>();
            other.add("h4", "v4");
            h.addAll(other);
            assertEquals("v4", h.getFirst("h4"));
            h.setAll(Collections.singletonMap("h5", "v5"));
            assertEquals(5, h.size());
            assertTrue(h.containsKey("h5"));
            assertTrue(h.containsValue(Arrays.asList("a", "b")));
            Map<String, String> single = h.toSingleValueMap();
            assertEquals("v1", single.get("h1"));
            h.put("h6", Collections.singletonList("p1"));
            assertEquals(Collections.singletonList("p1"), h.get("h6"));
            h.putAll(Collections.singletonMap("h7", Collections.singletonList("p7")));
            assertTrue(h.keySet().containsAll(Arrays.asList("h1", "h7")));
            assertTrue(h.values().stream().anyMatch(v -> v.contains("p7")));
            assertTrue(h.entrySet().stream().anyMatch(e -> "h7".equals(e.getKey())));
            assertEquals(Collections.singletonList("p1"), h.remove("h6"));
            h.clear();
            assertTrue(h.isEmpty());
            assertNull(h.getFirst("h1"));
        } finally {
            setFlag(originalFlag);
            setHandle(originalHandle);
        }
    }

    @Test
    void delegateMode_writesThroughSharedSuperStorage() throws Exception {
        setFlag(false);
        setHandle(asMvmHandle());
        try {
            MultiValueMap<String, String> store = new LinkedMultiValueMap<>();
            WebHttpHeaders h = new WebHttpHeaders(store);
            h.add("Accept", "application/json");
            assertEquals("application/json", store.getFirst("Accept"));
            assertEquals("application/json", h.getFirst("Accept"));
        } finally {
            setFlag(originalFlag);
            setHandle(originalHandle);
        }
    }

    @Test
    void resolveDelegate_nullHandle_throwsIllegalState() throws Exception {
        setFlag(false);
        setHandle(null);
        try {
            assertThrows(IllegalStateException.class, WebHttpHeaders::new);
        } finally {
            setFlag(originalFlag);
            setHandle(originalHandle);
        }
    }

    @Test
    void resolveDelegate_invocationFailure_isWrappedAsRuntime() throws Exception {
        setFlag(false);
        setHandle(failingHandle());
        try {
            RuntimeException ex = assertThrows(RuntimeException.class, WebHttpHeaders::new);
            assertEquals("Failed to invoke asMultiValueMap()", ex.getMessage());
            assertEquals("boom", ex.getCause().getMessage());
        } finally {
            setFlag(originalFlag);
            setHandle(originalHandle);
        }
    }
}
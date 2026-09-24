package io.springperf.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

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
        return MethodHandles.lookup().findStatic(WebHttpHeadersCoverageTest.class, "asMvm",
                MethodType.methodType(Object.class, Object.class));
    }

    private static MethodHandle failingHandle() throws Exception {
        return MethodHandles.lookup().findStatic(WebHttpHeadersCoverageTest.class, "fail",
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

    /**
     * Content-Type 快路径（可写 Netty 视图）：读写必须直接落到 Netty headers 且语义与 Spring
     * {@code HttpHeaders.setContentType/getContentType} 一致（含 null 等价 remove 与缓存失效）。
     */
    @Test
    void contentTypeFastPath_writableNettyView_writesAndReadsThroughNetty() {
        io.netty.handler.codec.http.HttpHeaders netty = new io.netty.handler.codec.http.DefaultHttpHeaders(false);
        WebHttpHeaders h = new WebHttpHeaders(new NettyHttpHeadersAdapter(netty, true));

        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        assertEquals("application/json", netty.get("Content-Type"), "应直接写入 Netty headers");
        assertEquals(org.springframework.http.MediaType.APPLICATION_JSON, h.getContentType());
        assertSame(h.getContentType(), h.getContentType(), "解析结果应被缓存");

        org.springframework.http.MediaType textual = org.springframework.http.MediaType
                .parseMediaType("text/plain;charset=UTF-8");
        h.setContentType(textual);
        assertEquals(textual, h.getContentType(), "set 后缓存必须失效并重新解析");
        assertEquals("text/plain;charset=UTF-8", netty.get("Content-Type"));

        h.setContentType(null);
        assertNull(netty.get("Content-Type"), "null 等价 remove（对齐 Spring 语义）");
        assertNull(h.getContentType());
    }

    /** 只读 Netty 视图（请求侧）：写操作仍必须抛 UnsupportedOperationException，不得被快路径绕过。 */
    @Test
    void contentTypeFastPath_readOnlyView_stillRejectsWrite() {
        io.netty.handler.codec.http.HttpHeaders netty = new io.netty.handler.codec.http.DefaultHttpHeaders(false);
        netty.set("Content-Type", "application/json");
        WebHttpHeaders ro = new WebHttpHeaders(new NettyHttpHeadersAdapter(netty, false));

        assertEquals(org.springframework.http.MediaType.APPLICATION_JSON, ro.getContentType());
        assertThrows(UnsupportedOperationException.class,
                () -> ro.setContentType(org.springframework.http.MediaType.TEXT_PLAIN));
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

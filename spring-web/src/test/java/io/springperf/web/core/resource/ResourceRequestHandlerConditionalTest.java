package io.springperf.web.core.resource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

/** If-Range/条件 GET、不可满足区间、gzip 负缓存与映射前缀剥离。 */
@ExtendWith(MockitoExtension.class)
class ResourceRequestHandlerConditionalTest {

    @Mock
    WebServerHttpRequest request;
    @Mock
    WebServerHttpResponse response;

    private ResourceHandlerRegistration registration;
    private ResourceRequestHandler handler;
    private HttpHeaders requestHeaders;
    private HttpHeaders responseHeaders;

    @BeforeEach
    void setUp() {
        registration = new ResourceHandlerRegistration("/static/**");
        registration.addResourceLocations("classpath:/static/");
        handler = new ResourceRequestHandler(registration);
        requestHeaders = new HttpHeaders();
        responseHeaders = new HttpHeaders();
        lenient().when(request.getHeaders()).thenReturn(requestHeaders);
        lenient().when(response.getHeaders()).thenReturn(responseHeaders);
        lenient().when(request.getPath()).thenReturn("/static/file.txt");
    }

    private static ResourceRequestHandler handlerServing(Resource resource) {
        return new ResourceRequestHandler(new ResourceHandlerRegistration("/static/**")) {
            @Override
            protected Resource getResource(String path) {
                return resource;
            }
        };
    }

    private static Object callStatic(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = ResourceRequestHandler.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static Object call(ResourceRequestHandler target, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method m = ResourceRequestHandler.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static boolean ifRangeAllows(WebServerHttpRequest req, Resource resource) throws Exception {
        return (Boolean) callStatic("ifRangeAllows",
                new Class<?>[] { WebServerHttpRequest.class, Resource.class }, req, resource);
    }

    private static String etagOf(Resource resource) throws Exception {
        return (String) callStatic("computeEtag", new Class<?>[] { Resource.class }, resource);
    }

    @Test
    void ifRangeAbsentOrBlankAllowsRange(@TempDir Path dir) throws Exception {
        Resource resource = new FileSystemResource(createFile(dir));
        assertFalse(requestHeaders.containsKey(HttpHeaders.IF_RANGE));

        assertTrue(ifRangeAllows(request, resource));
        requestHeaders.set(HttpHeaders.IF_RANGE, "   ");
        assertTrue(ifRangeAllows(request, resource));
    }

    @Test
    void ifRangeEtagMustMatchExactly(@TempDir Path dir) throws Exception {
        Resource resource = new FileSystemResource(createFile(dir));
        String etag = etagOf(resource);

        requestHeaders.set(HttpHeaders.IF_RANGE, etag);
        assertTrue(ifRangeAllows(request, resource));
        requestHeaders.set(HttpHeaders.IF_RANGE, "\"definitely-not-the-etag\"");
        assertFalse(ifRangeAllows(request, resource));
    }

    @Test
    void ifRangeHttpDateAndMalformedValue(@TempDir Path dir) throws Exception {
        Resource resource = new FileSystemResource(createFile(dir));

        requestHeaders.set(HttpHeaders.IF_RANGE, "Wed, 21 Oct 2099 07:28:00 GMT");
        assertTrue(ifRangeAllows(request, resource), "未来日期表示资源未变更，应允许 Range");
        requestHeaders.set(HttpHeaders.IF_RANGE, "not-a-date");
        assertFalse(ifRangeAllows(request, resource), "无法解析的 If-Range 必须忽略 Range");
    }

    @Test
    void singleRangeBeyondEndReturns416() throws Exception {
        requestHeaders.set(HttpHeaders.RANGE, "bytes=99-100");

        handlerServing(new ByteArrayResource("0123456789".getBytes(StandardCharsets.UTF_8)))
                .handleResourceRequest(request, response);

        assertEquals("bytes */10", responseHeaders.getFirst(HttpHeaders.CONTENT_RANGE));
        verify(response).sendError(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
    }

    @Test
    void multiRangeWithOneSegmentBeyondEndReturns416() throws Exception {
        requestHeaders.set(HttpHeaders.RANGE, "bytes=0-1,99-100");

        handlerServing(new ByteArrayResource("0123456789".getBytes(StandardCharsets.UTF_8)))
                .handleResourceRequest(request, response);

        assertEquals("bytes */10", responseHeaders.getFirst(HttpHeaders.CONTENT_RANGE));
        verify(response).sendError(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
    }

    @Test
    void ifNoneMatchStarReturns304(@TempDir Path dir) throws Exception {
        Resource resource = new FileSystemResource(createFile(dir));
        requestHeaders.set(HttpHeaders.IF_NONE_MATCH, "*");

        handlerServing(resource).handleResourceRequest(request, response);

        verify(response).setStatusCode(HttpStatus.NOT_MODIFIED);
        assertNotNull(responseHeaders.getETag());
        assertTrue(responseHeaders.getLastModified() > 0);
    }

    @Test
    void ifNoneMatchMismatchDoesNotReturn304(@TempDir Path dir) throws Exception {
        Resource resource = new FileSystemResource(createFile(dir));
        requestHeaders.set(HttpHeaders.IF_NONE_MATCH, "\"stale\"");

        handlerServing(resource).handleResourceRequest(request, response);

        verify(response, never()).setStatusCode(HttpStatus.NOT_MODIFIED);
        verify(response).setStatusCode(HttpStatus.OK);
    }

    @Test
    void ifModifiedSinceInFutureReturns304(@TempDir Path dir) throws Exception {
        Resource resource = new FileSystemResource(createFile(dir));
        requestHeaders.setIfModifiedSince(System.currentTimeMillis() + 60_000);

        handlerServing(resource).handleResourceRequest(request, response);

        verify(response).setStatusCode(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void gzipProbeCachesNegativeResult(@TempDir Path dir) throws Exception {
        Resource resource = new FileSystemResource(createFile(dir));
        assertNull(call(handler, "getGzipResource",
                new Class<?>[] { Resource.class, WebServerHttpRequest.class }, resource, request),
                "无 Accept-Encoding 时不应探测 .gz");

        requestHeaders.set(HttpHeaders.ACCEPT_ENCODING, "gzip");
        assertNull(call(handler, "getGzipResource",
                new Class<?>[] { Resource.class, WebServerHttpRequest.class }, resource, request));
        assertNull(call(handler, "getGzipResource",
                new Class<?>[] { Resource.class, WebServerHttpRequest.class }, resource, request),
                "第二次应命中 NO_GZIP 负缓存");

        @SuppressWarnings("unchecked")
        ConcurrentMap<String, Resource> cache = (ConcurrentMap<String, Resource>) readField(handler,
                "gzipResourceCache");
        assertEquals(1, cache.size());
        java.lang.reflect.Field marker = ResourceRequestHandler.class.getDeclaredField("NO_GZIP");
        marker.setAccessible(true);
        assertTrue(cache.containsValue(marker.get(null)), "应缓存 NO_GZIP 哨兵而不是反复探测文件系统");
    }

    @Test
    void stripMappedPrefixHandlesExactAndNestedPaths() throws Exception {
        handler.updateMappedPatterns(java.util.Collections.singletonList("/res/**"));

        assertEquals("/a.txt", call(handler, "stripMappedPrefix", new Class<?>[] { String.class }, "/res/a.txt"));
        assertEquals("/", call(handler, "stripMappedPrefix", new Class<?>[] { String.class }, "/res"));
        assertEquals("/other/a.txt",
                call(handler, "stripMappedPrefix", new Class<?>[] { String.class }, "/other/a.txt"));
    }

    private static Path createFile(Path dir) throws Exception {
        Path file = dir.resolve("file.txt");
        Files.write(file, "0123456789".getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static Object readField(Object target, String name) throws Exception {
        java.lang.reflect.Field field = ResourceRequestHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}

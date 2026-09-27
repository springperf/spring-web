package io.springperf.web.core.resource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import io.springperf.web.core.mapping.match.HttpMethodMatcher;
import io.springperf.web.core.mapping.match.Matcher;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

@ExtendWith(MockitoExtension.class)
class ResourceRequestHandlerTest {

    private ResourceHandlerRegistration registration;
    private ResourceRequestHandler handler;

    @Mock
    private WebServerHttpRequest request;
    @Mock
    private WebServerHttpResponse response;

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
    }

    // ----- 多段 Range 段数上限（server.http.max-ranges）-----

    /** 固定内容的资源替身，避免依赖测试资源文件（覆盖 protected getResource 钩子）。 */
    private ResourceRequestHandler handlerServingFixedContent() {
        return new ResourceRequestHandler(registration) {
            @Override
            protected org.springframework.core.io.Resource getResource(String path) {
                return new org.springframework.core.io.ByteArrayResource("0123456789".getBytes());
            }
        };
    }

    private void stubMaxRanges(String value) {
        io.springperf.web.context.ApplicationProperties props = mock(
                io.springperf.web.context.ApplicationProperties.class);
        io.springperf.web.context.WebContext webContext = mock(io.springperf.web.context.WebContext.class);
        lenient().when(webContext.getProps()).thenReturn(props);
        lenient().when(props.get(io.springperf.web.context.PropertiesConstant.HTTP_MAX_RANGES, null)).thenReturn(value);
        lenient().when(request.getWebContext()).thenReturn(webContext);
        lenient().when(request.getPath()).thenReturn("/static/e2e-range.txt");
    }

    /** 上限=2 时 3 段被忽略 → 整实体 200（不 206、不 multipart）。 */
    @Test
    void handleResourceRequest_multiRangeAboveConfiguredLimit_fallsBackToWholeEntity() throws Exception {
        stubMaxRanges("2");
        requestHeaders.set(HttpHeaders.RANGE, "bytes=0-0,1-1,2-2");

        handlerServingFixedContent().handleResourceRequest(request, response);

        verify(response).setStatusCode(HttpStatus.OK);
        verify(response, never()).setStatusCode(HttpStatus.PARTIAL_CONTENT);
    }

    /** 上限=2 时 2 段仍走 multipart/byteranges 206（边界含等于）。 */
    @Test
    void handleResourceRequest_multiRangeAtConfiguredLimit_servedAsMultipart() throws Exception {
        stubMaxRanges("2");
        requestHeaders.set(HttpHeaders.RANGE, "bytes=0-0,1-1");

        handlerServingFixedContent().handleResourceRequest(request, response);

        verify(response).setStatusCode(HttpStatus.PARTIAL_CONTENT);
    }

    /** 未配置时不因上下文缺失而收紧：走默认上限（多段正常 206）。 */
    @Test
    void handleResourceRequest_withoutWebContext_usesDefaultLimit() throws Exception {
        // 不桩 getWebContext（返回 null）→ 应回退默认上限而非抛异常/收紧
        lenient().when(request.getPath()).thenReturn("/static/e2e-range.txt");
        requestHeaders.set(HttpHeaders.RANGE, "bytes=0-0,1-1");

        handlerServingFixedContent().handleResourceRequest(request, response);

        verify(response).setStatusCode(HttpStatus.PARTIAL_CONTENT);
    }

    // ----- 多段 Range 段数上限（server.http.max-ranges 的判定内核）-----

    @Test
    void exceedsMaxRanges_negativeLimit_neverExceeds() {
        // 负值 = 本层不额外限制（底层 HttpRange 解析器仍有 100 段固有限制，见 PropertiesConstant）
        assertFalse(ResourceRequestHandler.exceedsMaxRanges(10_000, -1), "上限为负 = 本层不限");
    }

    @Test
    void exceedsMaxRanges_aboveParserCap_isStillAllowedByThisLayer() {
        // 本键只收紧不收放：设成 >100 时本层放行，实际由解析器的 100 段上限拒绝（忽略 Range）
        assertFalse(ResourceRequestHandler.exceedsMaxRanges(150, 200), "本层放行，交由解析器上限拒绝");
    }

    @Test
    void exceedsMaxRanges_boundaryIsInclusive() {
        assertFalse(ResourceRequestHandler.exceedsMaxRanges(100, 100), "恰好等于上限应放行");
        assertTrue(ResourceRequestHandler.exceedsMaxRanges(101, 100), "超一段即超限");
    }

    @Test
    void exceedsMaxRanges_zero_disablesMultipart() {
        assertTrue(ResourceRequestHandler.exceedsMaxRanges(2, 0), "0 = 禁止多段（任何多段请求都回退整实体）");
    }

    // ----- reformatPath -----

    @Test
    void reformatPath_matchingPattern_stripsPrefix() {
        assertEquals("/css/style.css", handler.reformatPath("/static/css/style.css"));
    }

    @Test
    void reformatPath_doubleWildcard_stripsCorrectPrefix() {
        ResourceHandlerRegistration reg = new ResourceHandlerRegistration("/resources/**");
        reg.addResourceLocations("classpath:/static/");
        ResourceRequestHandler h = new ResourceRequestHandler(reg);
        assertEquals("/js/app.js", h.reformatPath("/resources/js/app.js"));
    }

    @Test
    void reformatPath_singleWildcard_stripsCorrectPrefix() {
        ResourceHandlerRegistration reg = new ResourceHandlerRegistration("/files/*");
        reg.addResourceLocations("classpath:/static/");
        ResourceRequestHandler h = new ResourceRequestHandler(reg);
        assertEquals("/data.json", h.reformatPath("/files/data.json"));
    }

    @Test
    void reformatPath_directoryTraversal_returnsNull() {
        assertNull(handler.reformatPath("/static/../../etc/passwd"));
    }

    @Test
    void reformatPath_noMatchingPattern_returnsOriginalPath() {
        assertEquals("/other/file.txt", handler.reformatPath("/other/file.txt"));
    }

    @Test
    void reformatPath_rootPath_returnsSlash() {
        assertEquals("/", handler.reformatPath("/static/"));
    }

    // ----- getResource -----

    @Test
    void getResource_existing_returnsResource() {
        Resource resource = handler.getResource("/css/style.css");
        assertNotNull(resource);
        assertTrue(resource.exists());
    }

    @Test
    void getResource_nonExistent_returnsNull() {
        assertNull(handler.getResource("/non-existent-file.txt"));
    }

    @Test
    void getResource_existingWithContentLength() throws IOException {
        Resource resource = handler.getResource("/css/style.css");
        assertNotNull(resource);
        assertTrue(resource.contentLength() > 0);
    }

    @Test
    void getResource_multipleLocations_fallsBack() {
        ResourceHandlerRegistration reg = new ResourceHandlerRegistration("/static/**");
        reg.addResourceLocations("classpath:/nonexistent/");
        reg.addResourceLocations("classpath:/static/");
        ResourceRequestHandler h = new ResourceRequestHandler(reg);
        assertNotNull(h.getResource("/css/style.css"));
    }

    // ----- handleResourceRequest -----

    @Test
    void handleResourceRequest_found_returns200() {
        when(request.getPath()).thenReturn("/static/css/style.css");

        handler.handleResourceRequest(request, response);

        verify(response).setStatusCode(HttpStatus.OK);
        // 长度已知 → 走 Content-Length 帧的流式重载（chunked 会丢掉 Content-Length）
        verify(response).writeStream(any(InputStream.class), anyLong());
    }

    @Test
    void handleResourceRequest_notFound_returns404() {
        when(request.getPath()).thenReturn("/static/non-existent.txt");

        handler.handleResourceRequest(request, response);

        verify(response).sendError(HttpStatus.NOT_FOUND);
    }

    @Test
    void handleResourceRequest_pathTraversal_returns404() {
        when(request.getPath()).thenReturn("/static/../../etc/passwd");

        handler.handleResourceRequest(request, response);

        verify(response).sendError(HttpStatus.NOT_FOUND);
    }

    @Test
    void handleResourceRequest_setsContentType() {
        when(request.getPath()).thenReturn("/static/css/style.css");

        handler.handleResourceRequest(request, response);

        verify(response, atLeastOnce()).getHeaders();
        assertEquals("text/css", responseHeaders.getContentType().toString());
    }

    @Test
    void handleResourceRequest_setsContentLength() {
        when(request.getPath()).thenReturn("/static/css/style.css");

        handler.handleResourceRequest(request, response);

        assertTrue(responseHeaders.getContentLength() > 0);
    }

    @Test
    void handleResourceRequest_cacheControl_whenConfigured() {
        registration.setCachePeriod(3600);
        when(request.getPath()).thenReturn("/static/css/style.css");

        handler.handleResourceRequest(request, response);

        assertEquals("max-age=3600, must-revalidate", responseHeaders.getCacheControl());
    }

    @Test
    void handleResourceRequest_noCache_whenCachePeriodIsZero() {
        registration.setCachePeriod(0);
        when(request.getPath()).thenReturn("/static/css/style.css");

        handler.handleResourceRequest(request, response);

        assertEquals("no-cache, no-store, must-revalidate", responseHeaders.getCacheControl());
    }

    @Test
    void handleResourceRequest_welcomePage_indexHtml() {
        when(request.getPath()).thenReturn("/static/");

        handler.handleResourceRequest(request, response);

        verify(response).setStatusCode(HttpStatus.OK);
    }

    @Test
    void handleResourceRequest_welcomePage_subDirectory() {
        ResourceHandlerRegistration reg = new ResourceHandlerRegistration("/static/**");
        reg.addResourceLocations("classpath:/static/");
        ResourceRequestHandler h = new ResourceRequestHandler(reg);

        when(request.getPath()).thenReturn("/static/sub/");

        h.handleResourceRequest(request, response);

        verify(response).setStatusCode(HttpStatus.OK);
    }

    @Test
    void handleResourceRequest_notModified_whenIfModifiedSinceFresh() throws IOException {
        ClassPathResource testResource = new ClassPathResource("static/css/style.css");
        long lastModified = testResource.lastModified();

        when(request.getPath()).thenReturn("/static/css/style.css");
        requestHeaders.setIfModifiedSince(lastModified + 10000);

        handler.handleResourceRequest(request, response);

        verify(response).setStatusCode(HttpStatus.NOT_MODIFIED);
        verify(response, never()).writeStream(any());
    }

    @Test
    void handleResourceRequest_notModified_whenIfNoneMatchMatches() throws IOException {
        ClassPathResource testResource = new ClassPathResource("static/css/style.css");
        String expectedEtag = "\"0x" + Long.toHexString(testResource.lastModified()) + "-"
                + testResource.contentLength() + "\"";

        when(request.getPath()).thenReturn("/static/css/style.css");
        requestHeaders.setIfNoneMatch(expectedEtag);

        handler.handleResourceRequest(request, response);

        verify(response).setStatusCode(HttpStatus.NOT_MODIFIED);
    }

    @Test
    void handleResourceRequest_fullResponse_whenIfNoneMatchNotMatching() {
        when(request.getPath()).thenReturn("/static/css/style.css");
        requestHeaders.setIfNoneMatch("\"invalid-etag\"");

        handler.handleResourceRequest(request, response);

        verify(response).setStatusCode(HttpStatus.OK);
    }

    @Test
    void handleResourceRequest_setsEtagAndLastModified() {
        when(request.getPath()).thenReturn("/static/css/style.css");

        handler.handleResourceRequest(request, response);

        assertNotNull(responseHeaders.getETag());
        assertTrue(responseHeaders.getLastModified() > 0);
    }

    @Test
    void handleResourceRequest_ioError_sends500() throws Exception {
        when(request.getPath()).thenReturn("/static/css/style.css");
        doThrow(new RuntimeException("mock error")).when(response).writeStream(any());

        handler.handleResourceRequest(request, response);

        verify(response).sendError(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // ----- meta info -----

    @Test
    void getHandleMethod_returnsValidMethod() {
        Method method = handler.getHandleMethod();
        assertNotNull(method);
        assertEquals("handleResourceRequest", method.getName());
    }

    @Test
    void getType_returnsResource() {
        assertEquals("Resource", handler.getType());
    }

    @Test
    void getMatchers_containsHttpGetMethodMatcher() {
        List<Matcher> matchers = handler.getMatchers();
        assertEquals(1, matchers.size());
        assertTrue(matchers.get(0) instanceof HttpMethodMatcher);
    }

    // ----- cache -----

    @Test
    void clearCache_doesNotThrow() {
        handler.getResource("/css/style.css");
        assertDoesNotThrow(() -> handler.clearCache());
    }

    @Test
    void getResource_returnsResourceAfterCache() {
        Resource r1 = handler.getResource("/css/style.css");
        assertNotNull(r1);
        Resource r2 = handler.getResource("/css/style.css");
        assertNotNull(r2);
        assertTrue(r2.exists());
    }

    // ----- Accept-Ranges 宣告（RFC 9110 §14.3）-----

    @Test
    void handleResourceRequest_advertisesAcceptRanges_whenRangeServable() {
        when(request.getPath()).thenReturn("/static/e2e-range.txt");

        handlerServingFixedContent().handleResourceRequest(request, response);

        assertEquals("bytes", responseHeaders.getFirst(HttpHeaders.ACCEPT_RANGES));
    }

    /**
     * 回归：gzip 预压缩变体回退整实体、根本不处理 Range，却无条件宣告 {@code Accept-Ranges: bytes} —— 等于对外声称一种并不提供的能力，会让 CDN / 缓存据以发起 Range
     * 请求却拿回整实体。
     */
    @Test
    void handleResourceRequest_doesNotAdvertiseAcceptRanges_forGzipVariant(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.txt");
        Files.write(file, "0123456789".getBytes(StandardCharsets.UTF_8));
        // 内容不必是真实 gzip：探测只校验 exists/readable
        Files.write(dir.resolve("a.txt.gz"), new byte[] { 0x1f, (byte) 0x8b });

        ResourceRequestHandler gzipHandler = new ResourceRequestHandler(registration) {
            @Override
            protected Resource getResource(String path) {
                return new FileSystemResource(file);
            }
        };
        requestHeaders.set(HttpHeaders.ACCEPT_ENCODING, "gzip");
        when(request.getPath()).thenReturn("/static/a.txt");

        gzipHandler.handleResourceRequest(request, response);

        assertFalse(responseHeaders.containsKey(HttpHeaders.ACCEPT_RANGES), "gzip 变体不处理 Range，不应宣告 Accept-Ranges");
    }
}

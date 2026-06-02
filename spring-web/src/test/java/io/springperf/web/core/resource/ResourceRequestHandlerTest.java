package io.springperf.web.core.resource;

import io.springperf.web.core.mapping.match.HttpMethodMatcher;
import io.springperf.web.core.mapping.match.Matcher;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
        verify(response).writeStream(any(InputStream.class));
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
        String expectedEtag = "\"0x" + Long.toHexString(testResource.lastModified()) + "-" + testResource.contentLength() + "\"";

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
}

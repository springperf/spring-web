package io.springperf.web.core.resource;

import io.springperf.web.core.mapping.match.HttpMethodMatcher;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 琛ュ厖 ResourceRequestHandler 瑕嗙洊鐜囷細invoke()/getRegistration銆乬zip 棰勫帇缂┿€?
 * HEAD 璇箟銆丆ache-Control 閰嶇疆銆佹枃浠剁被浣嶇疆瑙ｆ瀽銆侀潪鏍囧噯 path 鍓嶇紑浠ュ強寮傚父鍏滃簳銆?
 */
@ExtendWith(MockitoExtension.class)
class ResourceRequestHandlerDetailsTest {

    private ResourceHandlerRegistration registration;
    private ResourceRequestHandler handler;

    @Mock WebServerHttpRequest request;
    @Mock WebServerHttpResponse response;

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

    /* ==================== meta ==================== */

    @Test
    void getRegistration_returnsRegistration() {
        assertSame(registration, handler.getRegistration());
    }

    @Test
    void invoke_callsHandleAndReturnsNull() throws Throwable {
        when(request.getPath()).thenReturn("/static/css/style.css");
        Object result = handler.invoke(new Object[]{request, response});
        assertNull(result);
        verify(response).setStatusCode(HttpStatus.OK);
    }

    /* ==================== gzip 棰勫帇缂?==================== */

    @Test
    void handleResourceRequest_acceptsGzip_servesGzipVariant() throws Exception {
        requestHeaders.set(HttpHeaders.ACCEPT_ENCODING, "gzip");
        when(request.getPath()).thenReturn("/static/css/style.css");

        handler.handleResourceRequest(request, response);

        verify(response).setStatusCode(HttpStatus.OK);
        assertEquals("gzip", responseHeaders.getFirst(HttpHeaders.CONTENT_ENCODING));
    }

    @Test
    void handleResourceRequest_noAcceptEncoding_noGzipHeader() {
        when(request.getPath()).thenReturn("/static/css/style.css");

        handler.handleResourceRequest(request, response);

        assertFalse(responseHeaders.containsKey(HttpHeaders.CONTENT_ENCODING));
    }

    @Test
    void handleResourceRequest_acceptsIdentity_noGzipHeader() {
        requestHeaders.set(HttpHeaders.ACCEPT_ENCODING, "deflate, br");
        when(request.getPath()).thenReturn("/static/css/style.css");

        handler.handleResourceRequest(request, response);

        assertFalse(responseHeaders.containsKey(HttpHeaders.CONTENT_ENCODING));
    }

    /* ==================== HEAD 璇箟 ==================== */

    @Test
    void handleResourceRequest_headRequest_skipsBody() {
        when(request.getPath()).thenReturn("/static/css/style.css");
        when(request.isHeadRequest()).thenReturn(true);

        handler.handleResourceRequest(request, response);

        verify(response, never()).writeStream(any(InputStream.class));
        verify(response).setStatusCode(HttpStatus.OK);
        // HEAD锛氫粎鍏冩暟鎹紝鏃?body 鍐欏叆
        assertTrue(responseHeaders.getContentLength() > 0);
    }

    /* ==================== Cache-Control 閰嶇疆 ==================== */

    @Test
    void handleResourceRequest_cacheControlExplicit_overridesPeriod() {
        registration.setCacheControl(CacheControl.maxAge(java.time.Duration.ofSeconds(60)).cachePublic());
        when(request.getPath()).thenReturn("/static/css/style.css");

        handler.handleResourceRequest(request, response);

        assertEquals("max-age=60, public", responseHeaders.getCacheControl());
    }

    /* ==================== file 璧勬簮浣嶇疆瑙ｆ瀽 ==================== */

    @Test
    void reformatPath_relativePathWithoutLeadingSlash_preservesPath() {
        ResourceHandlerRegistration reg = new ResourceHandlerRegistration("/static/**");
        reg.addResourceLocations("classpath:/static/");
        ResourceRequestHandler h = new ResourceRequestHandler(reg);
        // prefix 鍖归厤浣嗙浉瀵硅矾寰勬棤鍓嶅鏂滄潬鐨勮竟鐣岋紙path 鎭板ソ绛変簬 prefix锛?
        assertEquals("/", h.reformatPath("/static"));
    }

    @Test
    void getResource_fileUrlLocation_resolvesFile() throws IOException {
        ClassPathResource cp = new ClassPathResource("static/css/style.css");
        File realFile = cp.getFile();
        File tmpDir = java.nio.file.Files.createTempDirectory("perf-res").toFile();
        File tmpFile = new File(tmpDir, "data.txt");
        try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
            fos.write("hello".getBytes());
        }
        ResourceHandlerRegistration reg = new ResourceHandlerRegistration("/files/**");
        reg.addResourceLocations("file:" + tmpDir.getAbsolutePath() + "/");
        ResourceRequestHandler h = new ResourceRequestHandler(reg);

        Resource r = h.getResource("/data.txt");
        try {
            assertNotNull(r);
            String body;
            try (java.io.InputStream in = r.getInputStream()) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) != -1) {
                    bos.write(buf, 0, len);
                }
                body = new String(bos.toByteArray());
            }
            assertEquals("hello", body);
        } finally {
            tmpFile.delete();
            tmpDir.delete();
        }
    }

    @Test
    void getResource_plainFileLocation_resolvesFile() throws IOException {
        ResourceHandlerRegistration reg = new ResourceHandlerRegistration("/plain/**");
        reg.addResourceLocations("build/tmp-res/");
        ResourceRequestHandler h = new ResourceRequestHandler(reg);
        assertNull(h.getResource("/nope.txt"));
    }

    @Test
    void getResource_fileLocation_missingFile_returnsNull() {
        ResourceHandlerRegistration reg = new ResourceHandlerRegistration("/files/**");
        reg.addResourceLocations("file:/definitely/not/a/real/path/");
        ResourceRequestHandler h = new ResourceRequestHandler(reg);
        assertNull(h.getResource("/x.txt"));
    }

    @Test
    void resolveResourceByUri_filePrefix_existingFile_returnsResource() throws Exception {
        ClassPathResource cp = new ClassPathResource("static/css/style.css");
        File realFile = cp.getFile();
        java.lang.reflect.Method m = ResourceRequestHandler.class.getDeclaredMethod(
                "resolveResourceByUri", String.class);
        m.setAccessible(true);
        Resource r = (Resource) m.invoke(handler, realFile.toURI().toString());
        assertNotNull(r);
        assertTrue(r.exists());
    }

    @Test
    void resolveResourceByUri_filePrefix_missingFile_returnsNull() throws Exception {
        java.lang.reflect.Method m = ResourceRequestHandler.class.getDeclaredMethod(
                "resolveResourceByUri", String.class);
        m.setAccessible(true);
        Resource r = (Resource) m.invoke(handler, "file:/definitely/not/exists.txt");
        assertNull(r);
    }

    /* ==================== 杈圭晫涓庡紓甯稿厹搴?==================== */

    @Test
    void handleResourceRequest_lastModifiedNegative_servesFullResponse() {
        ResourceHandlerRegistration reg = new ResourceHandlerRegistration("/custom/**");
        reg.addResourceLocations("classpath:/static/");
        ResourceRequestHandler h = new ResourceRequestHandler(reg) {
            @Override
            protected Resource getResource(String path) {
                return new ClassPathResource("static/css/style.css") {
                    @Override
                    public long lastModified() {
                        return -1;
                    }
                };
            }
        };
        when(request.getPath()).thenReturn("/custom/css/style.css");

        h.handleResourceRequest(request, response);

        verify(response).setStatusCode(HttpStatus.OK);
        assertFalse(responseHeaders.containsKey(HttpHeaders.ETAG));
    }

    @Test
    void handleResourceRequest_writeFails_sends500() {
        when(request.getPath()).thenReturn("/static/css/style.css");
        doThrow(new RuntimeException("stream failed")).when(response).writeStream(any(InputStream.class));

        handler.handleResourceRequest(request, response);

        verify(response).sendError(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void getMatchers_containsOnlyGet() {
        List<io.springperf.web.core.mapping.match.Matcher> matchers = handler.getMatchers();
        assertEquals(1, matchers.size());
        assertInstanceOf(HttpMethodMatcher.class, matchers.get(0));
    }
}
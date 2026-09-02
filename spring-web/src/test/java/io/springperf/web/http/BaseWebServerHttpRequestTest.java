package io.springperf.web.http;

import io.springperf.web.context.WebContext;
import io.springperf.web.http.support.HttpInputMessagePart;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BaseWebServerHttpRequestTest {

    private WebContext webContext = mock(WebContext.class);

    /** 最小可测子类：仅实现抽象方法，暴露基类逻辑 */
    static class TestRequest extends BaseWebServerHttpRequest {
        private final HttpHeaders headers;
        private final MultiValueMap<String, String> params;

        TestRequest(WebContext wc, String uriStrWithQuery, String resolvedPath, HttpHeaders headers,
                    MultiValueMap<String, String> params) {
            super(wc, uriStrWithQuery, resolvedPath);
            this.headers = headers;
            this.params = params;
        }

        @Override
        protected MultiValueMap<String, String> parseParameters() {
            return params;
        }

        @Override
        public HttpMethod getMethod() {
            return HttpMethod.GET;
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public URI getURI() {
            return URI.create(getUriStrWithQuery());
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public boolean hasBody() {
            return false;
        }

        @Override
        public int getContentLength() {
            return 0;
        }

        @Override
        public MultiValueMap<String, MultipartFile> getMultiFileMap() {
            return null;
        }

        @Override
        public MultiValueMap<String, HttpInputMessagePart> getPartMap() {
            return null;
        }

        @Override
        public void acquire() {
        }

        @Override
        public boolean release() {
            return false;
        }
    }

    @Test
    void constructor_stripsQueryFromUriStr() {
        TestRequest req = new TestRequest(webContext, "/api/users?id=1&a=2", "/api/users", new HttpHeaders(), null);
        assertEquals("/api/users?id=1&a=2", req.getUriStrWithQuery());
        assertEquals("/api/users", req.getUriStr());
        assertEquals("/api/users", req.getPath());
    }

    @Test
    void constructor_noQuery_uriStrEqualsUriStrWithQuery() {
        TestRequest req = new TestRequest(webContext, "/api/users", "/api/users", new HttpHeaders(), null);
        assertEquals("/api/users", req.getUriStr());
        assertEquals("/api/users", req.getUriStrWithQuery());
    }

    @Test
    void getParameterMap_lazilyParsesOnce() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("a", "1");
        TestRequest req = new TestRequest(webContext, "/test?a=1", "/test", new HttpHeaders(), params);
        assertSame(params, req.getParameterMap());
        assertSame(params, req.getParameterMap(), "第二次调用不应重新解析");
    }

    @Test
    void getParameter_firstAndAll() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("a", "1");
        params.add("a", "2");
        TestRequest req = new TestRequest(webContext, "/test", "/test", new HttpHeaders(), params);
        assertEquals("1", req.getParameter("a"));
        assertArrayEquals(new String[]{"1", "2"}, req.getParameterValues("a"));
        assertNull(req.getParameter("missing"));
        assertNull(req.getParameterValues("missing"));
    }

    @Test
    void getParameterMapArray_convertsToArrayMap() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("a", "1");
        params.add("a", "2");
        TestRequest req = new TestRequest(webContext, "/test", "/test", new HttpHeaders(), params);
        assertEquals(1, req.getParameterMapArray().size());
        assertArrayEquals(new String[]{"1", "2"}, req.getParameterMapArray().get("a"));
    }

    @Test
    void characterEncoding_defaultUtf8_andSettable() {
        TestRequest req = new TestRequest(webContext, "/test", "/test", new HttpHeaders(), null);
        assertEquals(StandardCharsets.UTF_8, req.getCharacterEncoding());
        req.setCharacterEncoding(StandardCharsets.ISO_8859_1);
        assertEquals(StandardCharsets.ISO_8859_1, req.getCharacterEncoding());
    }

    @Test
    void attributes_stringBased() {
        TestRequest req = new TestRequest(webContext, "/test", "/test", new HttpHeaders(), null);
        assertNull(req.getAttribute("k"));
        req.setAttribute("k", "v");
        assertEquals("v", req.getAttribute("k"));
        assertEquals("v", req.removeAttribute("k"));
        assertNull(req.getAttribute("k"));
        assertEquals(0, req.getAttributes().size());
    }

    @Test
    void attributes_typedKeys() {
        TestRequest req = new TestRequest(webContext, "/test", "/test", new HttpHeaders(), null);
        RequestAttribute<String> key = RequestAttribute.createAttribute(String.class);
        assertNull(req.getAttribute(key));
        req.setAttribute(key, "typed");
        assertEquals("typed", req.getAttribute(key));
    }

    @Test
    void attributes_typedKeyBeyondFastArray_fallsBackToMap() {
        TestRequest req = new TestRequest(webContext, "/test", "/test", new HttpHeaders(), null);
        // 构造一个 index 超出 fastAttributes 的 key（通过反射构造）
        RequestAttribute<String> key = new RequestAttribute<>(RequestAttribute.getMaxSize() + 100, String.class);
        req.setAttribute(key, "beyond");
        assertEquals("beyond", req.getAttribute(key));
    }

    @Test
    void filterIndex_increments() {
        TestRequest req = new TestRequest(webContext, "/test", "/test", new HttpHeaders(), null);
        assertEquals(0, req.getFilterIndexAndIncrement());
        assertEquals(1, req.getFilterIndexAndIncrement());
        assertEquals(2, req.getFilterIndexAndIncrement());
    }

    @Test
    void getRequestContext_returnsSelf() {
        TestRequest req = new TestRequest(webContext, "/test", "/test", new HttpHeaders(), null);
        assertSame(req, req.getRequestContext());
    }

    @Test
    void getPrincipal_isNull() {
        TestRequest req = new TestRequest(webContext, "/test", "/test", new HttpHeaders(), null);
        assertNull(req.getPrincipal());
    }

    @Test
    void getLocales_noHeader_usesDefault() {
        TestRequest req = new TestRequest(webContext, "/test", "/test", new HttpHeaders(), null);
        List<Locale> locales = req.getLocales();
        assertEquals(1, locales.size());
        assertEquals(Locale.getDefault(), locales.get(0));
        assertEquals(Locale.getDefault(), req.getLocale());
    }

    @Test
    void getLocales_acceptLanguage_parsesAndCaches() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept-Language", "en-US,fr;q=0.9");
        TestRequest req = new TestRequest(webContext, "/test", "/test", headers, null);
        List<Locale> locales = req.getLocales();
        assertEquals(2, locales.size());
        assertEquals(Locale.forLanguageTag("en-US"), locales.get(0));
        assertEquals(Locale.forLanguageTag("fr"), locales.get(1));
        // 二次调用命中缓存（同一实例 locales 已缓存）
        assertSame(locales, req.getLocales());
    }

    @Test
    void getLocales_acceptLanguageWildcard_skipsAsterisk() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept-Language", "*,en;q=0.5");
        TestRequest req = new TestRequest(webContext, "/test", "/test", headers, null);
        List<Locale> locales = req.getLocales();
        assertFalse(locales.contains(Locale.forLanguageTag("*")));
        assertEquals(Locale.forLanguageTag("en"), locales.get(0));
    }

    @Test
    void getLocales_invalidHeader_fallsBackToDefault() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept-Language", "not a valid range @@@");
        TestRequest req = new TestRequest(webContext, "/test", "/test", headers, null);
        assertEquals(Arrays.asList(Locale.getDefault()), req.getLocales());
    }

    @Test
    void acceptLanguageLocaleCache_getAndPut() {
        List<Locale> locales = Arrays.asList(Locale.US);
        BaseWebServerHttpRequest.AcceptLanguageLocaleCache.put("cache-test", locales);
        assertSame(locales, BaseWebServerHttpRequest.AcceptLanguageLocaleCache.get("cache-test"));
        assertNull(BaseWebServerHttpRequest.AcceptLanguageLocaleCache.get("not-exists"));
    }
}

package io.springperf.web.http;

import io.springperf.web.context.WebContext;
import io.springperf.web.http.support.HttpInputMessagePart;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.ServerHttpAsyncRequestControl;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebServerHttpRequestWrapperTest {

    private final WebServerHttpRequest delegate = mock(WebServerHttpRequest.class);
    private final WebServerHttpRequestWrapper wrapper = new WebServerHttpRequestWrapper(delegate);

    @Test void getRequest_returnsDelegate() { assertSame(delegate, wrapper.getRequest()); }
    @Test void getUriStrWithQuery_delegates() { when(delegate.getUriStrWithQuery()).thenReturn("/api?q=1"); assertEquals("/api?q=1", wrapper.getUriStrWithQuery()); }
    @Test void getUriStr_delegates() { when(delegate.getUriStr()).thenReturn("/api"); assertEquals("/api", wrapper.getUriStr()); }
    @Test void getPath_delegates() { when(delegate.getPath()).thenReturn("/api/test"); assertEquals("/api/test", wrapper.getPath()); }

    @Test void getParameterMap_delegates() {
        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add("key", "value");
        when(delegate.getParameterMap()).thenReturn(map);
        assertSame(map, wrapper.getParameterMap());
    }

    @Test void getParameterMapArray_delegates() {
        Map<String, String[]> map = Collections.singletonMap("key", new String[]{"v1"});
        when(delegate.getParameterMapArray()).thenReturn(map);
        assertSame(map, wrapper.getParameterMapArray());
    }

    @Test void getParameter_delegates() { when(delegate.getParameter("name")).thenReturn("value"); assertEquals("value", wrapper.getParameter("name")); }
    @Test void getParameterValues_delegates() { when(delegate.getParameterValues("n")).thenReturn(new String[]{"a", "b"}); assertArrayEquals(new String[]{"a", "b"}, wrapper.getParameterValues("n")); }
    @Test void getMultiFileMap_delegates() { MultiValueMap<String, MultipartFile> map = new LinkedMultiValueMap<>(); when(delegate.getMultiFileMap()).thenReturn(map); assertSame(map, wrapper.getMultiFileMap()); }
    @Test void getPartMap_delegates() { MultiValueMap<String, HttpInputMessagePart> map = new LinkedMultiValueMap<>(); when(delegate.getPartMap()).thenReturn(map); assertSame(map, wrapper.getPartMap()); }
    @Test void getCharacterEncoding_delegates() { when(delegate.getCharacterEncoding()).thenReturn(Charset.forName("UTF-8")); assertEquals(Charset.forName("UTF-8"), wrapper.getCharacterEncoding()); }
    @Test void setCharacterEncoding_delegates() { wrapper.setCharacterEncoding(Charset.forName("ISO-8859-1")); verify(delegate).setCharacterEncoding(Charset.forName("ISO-8859-1")); }
    @Test void getContentLength_delegates() { when(delegate.getContentLength()).thenReturn(42); assertEquals(42, wrapper.getContentLength()); }
    @Test void getLocales_delegates() { List<Locale> locales = Collections.singletonList(Locale.US); when(delegate.getLocales()).thenReturn(locales); assertSame(locales, wrapper.getLocales()); }
    @Test void getLocale_delegates() { when(delegate.getLocale()).thenReturn(Locale.CHINA); assertEquals(Locale.CHINA, wrapper.getLocale()); }
    @Test void getWebContext_delegates() { WebContext ctx = mock(WebContext.class); when(delegate.getWebContext()).thenReturn(ctx); assertSame(ctx, wrapper.getWebContext()); }
    @Test void getRequestContext_delegates() { RequestContext ctx = mock(RequestContext.class); when(delegate.getRequestContext()).thenReturn(ctx); assertSame(ctx, wrapper.getRequestContext()); }
    @Test void getPrincipal_delegates() { Principal p = mock(Principal.class); when(delegate.getPrincipal()).thenReturn(p); assertSame(p, wrapper.getPrincipal()); }
    @Test void getLocalAddress_delegates() { InetSocketAddress addr = new InetSocketAddress(8080); when(delegate.getLocalAddress()).thenReturn(addr); assertSame(addr, wrapper.getLocalAddress()); }
    @Test void getRemoteAddress_delegates() { InetSocketAddress addr = new InetSocketAddress("localhost", 12345); when(delegate.getRemoteAddress()).thenReturn(addr); assertSame(addr, wrapper.getRemoteAddress()); }
    @Test void getAsyncRequestControl_delegates() { ServerHttpResponse resp = mock(ServerHttpResponse.class); ServerHttpAsyncRequestControl ctrl = mock(ServerHttpAsyncRequestControl.class); when(delegate.getAsyncRequestControl(resp)).thenReturn(ctrl); assertSame(ctrl, wrapper.getAsyncRequestControl(resp)); }
    @Test void getMethod_delegates() { when(delegate.getMethod()).thenReturn(HttpMethod.POST); assertEquals(HttpMethod.POST, wrapper.getMethod()); }
    @Test void getMethodValue_delegates() { when(delegate.getMethodValue()).thenReturn("GET"); assertEquals("GET", wrapper.getMethodValue()); }
    @Test void getURI_delegates() { java.net.URI uri = java.net.URI.create("/test"); when(delegate.getURI()).thenReturn(uri); assertSame(uri, wrapper.getURI()); }
    @Test void getHeaders_delegates() { HttpHeaders h = new HttpHeaders(); when(delegate.getHeaders()).thenReturn(h); assertSame(h, wrapper.getHeaders()); }
    @Test void getBody_delegates() throws Exception { InputStream s = mock(InputStream.class); when(delegate.getBody()).thenReturn(s); assertSame(s, wrapper.getBody()); }
    @Test void hasBody_delegates() { when(delegate.hasBody()).thenReturn(true); assertTrue(wrapper.hasBody()); }
    @Test void acquire_delegates() { wrapper.acquire(); verify(delegate).acquire(); }
    @Test void release_delegates() { when(delegate.release()).thenReturn(true); assertTrue(wrapper.release()); }
}
package io.springperf.web.core.async;

import io.springperf.web.context.WebContext;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerfNativeWebRequestTest {

    @Mock WebServerHttpRequest request;
    @Mock WebServerHttpResponse response;
    @Mock RequestContext requestContext;
    @Mock WebContext webContext;

    PerfNativeWebRequest nativeWebRequest;

    @BeforeEach
    void setUp() {
        nativeWebRequest = new PerfNativeWebRequest(request, response);
    }

    @Test void getNativeRequest_returnsRequest() { assertSame(request, nativeWebRequest.getNativeRequest()); }
    @Test void getNativeResponse_returnsResponse() { assertSame(response, nativeWebRequest.getNativeResponse()); }
    @Test void getNativeRequest_withMatchingType_returnsRequest() { assertSame(request, nativeWebRequest.getNativeRequest(WebServerHttpRequest.class)); }
    @Test void getNativeRequest_withNonMatchingType_returnsNull() { assertNull(nativeWebRequest.getNativeRequest(String.class)); }
    @Test void getNativeResponse_withMatchingType_returnsResponse() { assertSame(response, nativeWebRequest.getNativeResponse(WebServerHttpResponse.class)); }
    @Test void getNativeResponse_withNonMatchingType_returnsNull() { assertNull(nativeWebRequest.getNativeResponse(String.class)); }

    @Test void getAttribute_delegatesToRequestContext() {
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute("key")).thenReturn("value");
        assertEquals("value", nativeWebRequest.getAttribute("key", 0));
    }

    @Test void setAttribute_delegatesToRequestContext() {
        when(request.getRequestContext()).thenReturn(requestContext);
        nativeWebRequest.setAttribute("key", "value", 0);
        verify(requestContext).setAttribute("key", "value");
    }

    @Test void removeAttribute_delegatesToRequestContext() {
        when(request.getRequestContext()).thenReturn(requestContext);
        nativeWebRequest.removeAttribute("key", 0);
        verify(requestContext).removeAttribute("key");
    }

    @Test void getAttributeNames_returnsKeys() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("k1", "v1");
        attrs.put("k2", "v2");
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttributes()).thenReturn(attrs);
        String[] names = nativeWebRequest.getAttributeNames(0);
        assertEquals(2, names.length);
        assertTrue(Arrays.asList(names).containsAll(Arrays.asList("k1", "k2")));
    }

    @Test void getHeader_delegatesToRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Accept", "application/json");
        when(request.getHeaders()).thenReturn(headers);
        assertEquals("application/json", nativeWebRequest.getHeader("Accept"));
    }

    @Test void getHeaderValues_delegatesToRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Accept", "text/html");
        headers.add("Accept", "application/json");
        when(request.getHeaders()).thenReturn(headers);
        String[] values = nativeWebRequest.getHeaderValues("Accept");
        assertEquals(2, values.length);
    }

    @Test void getHeaderNames_delegatesToRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Accept", "application/json");
        headers.add("Content-Type", "text/plain");
        when(request.getHeaders()).thenReturn(headers);
        Iterator<String> names = nativeWebRequest.getHeaderNames();
        Set<String> nameSet = new HashSet<>();
        names.forEachRemaining(nameSet::add);
        assertEquals(2, nameSet.size());
        assertTrue(nameSet.contains("Accept"));
        assertTrue(nameSet.contains("Content-Type"));
    }

    @Test void getParameter_delegatesToRequest() { when(request.getParameter("name")).thenReturn("value"); assertEquals("value", nativeWebRequest.getParameter("name")); }
    @Test void getParameterValues_delegatesToRequest() { when(request.getParameterValues("name")).thenReturn(new String[]{"a","b"}); assertArrayEquals(new String[]{"a","b"}, nativeWebRequest.getParameterValues("name")); }

    @Test void getParameterNames_delegatesToRequest() {
        MultiValueMap<String, String> paramMap = new LinkedMultiValueMap<>();
        paramMap.add("p1", "v1");
        paramMap.add("p2", "v2");
        when(request.getParameterMap()).thenReturn(paramMap);
        Iterator<String> names = nativeWebRequest.getParameterNames();
        Set<String> nameSet = new HashSet<>();
        names.forEachRemaining(nameSet::add);
        assertEquals(2, nameSet.size());
    }

    @Test void getParameterMap_delegatesToRequest() {
        Map<String, String[]> expected = new HashMap<>();
        expected.put("name", new String[]{"value"});
        when(request.getParameterMapArray()).thenReturn(expected);
        assertSame(expected, nativeWebRequest.getParameterMap());
    }

    @Test void getLocale_delegatesToRequest() { when(request.getLocale()).thenReturn(Locale.CHINA); assertSame(Locale.CHINA, nativeWebRequest.getLocale()); }
    @Test void getContextPath_delegatesToRequest() { when(request.getWebContext()).thenReturn(webContext); when(webContext.getContextPath()).thenReturn("/app"); assertEquals("/app", nativeWebRequest.getContextPath()); }
    @Test void getRemoteUser_returnsNull() { assertNull(nativeWebRequest.getRemoteUser()); }
    @Test void getUserPrincipal_returnsNull() { assertNull(nativeWebRequest.getUserPrincipal()); }
    @Test void isUserInRole_returnsFalse() { assertFalse(nativeWebRequest.isUserInRole("admin")); }
    @Test void isSecure_returnsFalse() { assertFalse(nativeWebRequest.isSecure()); }
    @Test void checkNotModified_lastModified_returnsFalse() { assertFalse(nativeWebRequest.checkNotModified(1000L)); }
    @Test void checkNotModified_etag_returnsFalse() { assertFalse(nativeWebRequest.checkNotModified("\"etag\"")); }
    @Test void checkNotModified_both_returnsFalse() { assertFalse(nativeWebRequest.checkNotModified("\"etag\"", 1000L)); }
    @Test void getDescription_returnsNull() { assertNull(nativeWebRequest.getDescription(true)); }
    @Test void registerDestructionCallback_doesNotThrow() { nativeWebRequest.registerDestructionCallback("name", () -> {}, 0); }
    @Test void resolveReference_returnsNull() { assertNull(nativeWebRequest.resolveReference("key")); }
    @Test void getSessionId_returnsNull() { assertNull(nativeWebRequest.getSessionId()); }
    @Test void getSessionMutex_returnsNull() { assertNull(nativeWebRequest.getSessionMutex()); }
}
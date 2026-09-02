package io.springperf.web.core.mapping.match;

import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpMethodMatcherTest {

    private WebServerHttpRequest mockRequest(HttpMethod method) {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getMethod()).thenReturn(method);
        return req;
    }

    @Test
    void match_allowedMethod_returnsTrue() {
        HttpMethodMatcher matcher = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET});
        assertTrue(matcher.match(mockRequest(HttpMethod.GET), null));
    }

    @Test
    void match_disallowedMethod_returnsFalse() {
        HttpMethodMatcher matcher = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET});
        assertFalse(matcher.match(mockRequest(HttpMethod.POST), null));
    }

    @Test
    void match_multipleAllowedMethods_returnsTrue() {
        HttpMethodMatcher matcher = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET, HttpMethod.POST});
        assertTrue(matcher.match(mockRequest(HttpMethod.GET), null));
        assertTrue(matcher.match(mockRequest(HttpMethod.POST), null));
        assertFalse(matcher.match(mockRequest(HttpMethod.DELETE), null));
    }

    @Test
    void match_emptyMethods_returnsFalse() {
        HttpMethodMatcher matcher = new HttpMethodMatcher(new HttpMethod[]{});
        assertFalse(matcher.match(mockRequest(HttpMethod.GET), null));
    }

    @Test
    void isSameTypeMatcher_sameType_returnsTrue() {
        HttpMethodMatcher m1 = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET});
        HttpMethodMatcher m2 = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.POST});
        assertTrue(m1.isSameTypeMatcher(m2));
    }

    @Test
    void isSameTypeMatcher_differentType_returnsFalse() {
        HttpMethodMatcher m = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET});
        Matcher other = mock(Matcher.class);
        assertFalse(m.isSameTypeMatcher(other));
    }

    @Test
    void haveAmbiguous_oneContainsOther_returnsTrue() {
        HttpMethodMatcher m1 = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET, HttpMethod.POST});
        HttpMethodMatcher m2 = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET});
        assertTrue(m1.haveAmbiguous(m2));
        assertTrue(m2.haveAmbiguous(m1));
    }

    @Test
    void haveAmbiguous_overlappingButNeitherContains_returnsFalse() {
        HttpMethodMatcher m1 = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET, HttpMethod.POST});
        HttpMethodMatcher m2 = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.POST, HttpMethod.DELETE});
        assertFalse(m1.haveAmbiguous(m2));
    }

    @Test
    void haveAmbiguous_disjoint_returnsFalse() {
        HttpMethodMatcher m1 = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET});
        HttpMethodMatcher m2 = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.POST});
        assertFalse(m1.haveAmbiguous(m2));
    }

    @Test
    void haveAmbiguous_differentType_returnsFalse() {
        HttpMethodMatcher m = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET});
        assertFalse(m.haveAmbiguous(mock(Matcher.class)));
    }

    @Test
    void getHttpMethods_returnsConfiguredMethods() {
        HttpMethodMatcher matcher = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET, HttpMethod.PUT});
        assertTrue(matcher.getHttpMethods().contains(HttpMethod.GET));
        assertTrue(matcher.getHttpMethods().contains(HttpMethod.PUT));
        assertEquals(2, matcher.getHttpMethods().size());
    }

    @Test
    void toString_singleMethod() {
        HttpMethodMatcher matcher = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET});
        assertEquals("GET", matcher.toString());
    }

    @Test
    void toString_multipleMethods() {
        HttpMethodMatcher matcher = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET, HttpMethod.POST});
        String str = matcher.toString();
        assertTrue(str.contains("GET"));
        assertTrue(str.contains("POST"));
    }

    // ==================== HEAD 语义（RFC 7231 §4.3.2） ====================

    @Test
    void match_headToGet_mapsAndMarksRequest() {
        HttpMethodMatcher matcher = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET});
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getMethod()).thenReturn(HttpMethod.HEAD);

        assertTrue(matcher.match(req, null), "HEAD 应映射到 GET");
        verify(req).markAsHeadRequest();
    }

    @Test
    void match_headExplicit_mapsAndMarksRequest() {
        HttpMethodMatcher matcher = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.HEAD});
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getMethod()).thenReturn(HttpMethod.HEAD);

        assertTrue(matcher.match(req, null), "显式 HEAD 应匹配");
        verify(req).markAsHeadRequest();
    }

    @Test
    void match_headToUnsupportedMethod_returnsFalse_noMark() {
        HttpMethodMatcher matcher = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.POST});
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getMethod()).thenReturn(HttpMethod.HEAD);

        assertFalse(matcher.match(req, null), "无 GET/HEAD 时 HEAD 不应匹配");
        verify(req, never()).markAsHeadRequest();
    }

    @Test
    void match_nonHeadMethod_doesNotMark() {
        HttpMethodMatcher matcher = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET});
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getMethod()).thenReturn(HttpMethod.GET);

        assertTrue(matcher.match(req, null));
        verify(req, never()).markAsHeadRequest();
    }

    @Test
    void match_headWithGetAndPost_mapsAndMarks() {
        HttpMethodMatcher matcher = new HttpMethodMatcher(new HttpMethod[]{HttpMethod.GET, HttpMethod.POST});
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getMethod()).thenReturn(HttpMethod.HEAD);

        assertTrue(matcher.match(req, null));
        verify(req).markAsHeadRequest();
    }
}

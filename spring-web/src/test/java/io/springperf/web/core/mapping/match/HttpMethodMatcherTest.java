package io.springperf.web.core.mapping.match;

import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
}

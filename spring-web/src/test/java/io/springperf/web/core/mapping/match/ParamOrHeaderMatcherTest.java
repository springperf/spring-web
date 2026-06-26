package io.springperf.web.core.mapping.match;

import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParamOrHeaderMatcherTest {

    private MultiValueMap<String, String> createParamMap(String key, String value) {
        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add(key, value);
        return map;
    }

    @Test
    void match_paramExists_returnsTrue() {
        ParamOrHeaderMatcher matcher = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("foo")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getParameterMap()).thenReturn(createParamMap("foo", "bar"));

        assertTrue(matcher.match(req, null));
    }

    @Test
    void match_paramNotExists_returnsFalse() {
        ParamOrHeaderMatcher matcher = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("foo")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getParameterMap()).thenReturn(new LinkedMultiValueMap<>());

        assertFalse(matcher.match(req, null));
    }

    @Test
    void match_paramWithValue_returnsTrue() {
        ParamOrHeaderMatcher matcher = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("foo=bar")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getParameter("foo")).thenReturn("bar");

        assertTrue(matcher.match(req, null));
    }

    @Test
    void match_paramWithValueMismatch_returnsFalse() {
        ParamOrHeaderMatcher matcher = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("foo=bar")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getParameter("foo")).thenReturn("baz");

        assertFalse(matcher.match(req, null));
    }

    @Test
    void match_paramNegatedExists_returnsFalse() {
        ParamOrHeaderMatcher matcher = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("!foo")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getParameterMap()).thenReturn(createParamMap("foo", "bar"));

        assertFalse(matcher.match(req, null));
    }

    @Test
    void match_paramNegatedNotExists_returnsTrue() {
        ParamOrHeaderMatcher matcher = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("!foo")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getParameterMap()).thenReturn(new LinkedMultiValueMap<>());

        assertTrue(matcher.match(req, null));
    }

    @Test
    void match_headerExists_returnsTrue() {
        ParamOrHeaderMatcher matcher = new ParamOrHeaderMatcher(true,
                Collections.singletonList(NameValueExpressionSupport.build("X-Custom")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Custom", "value");
        when(req.getHeaders()).thenReturn(headers);

        assertTrue(matcher.match(req, null));
    }

    @Test
    void match_headerNotExists_returnsFalse() {
        ParamOrHeaderMatcher matcher = new ParamOrHeaderMatcher(true,
                Collections.singletonList(NameValueExpressionSupport.build("X-Custom")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getHeaders()).thenReturn(new HttpHeaders());

        assertFalse(matcher.match(req, null));
    }

    @Test
    void match_headerWithValue_returnsTrue() {
        ParamOrHeaderMatcher matcher = new ParamOrHeaderMatcher(true,
                Collections.singletonList(NameValueExpressionSupport.build("X-Custom=value")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Custom", "value");
        when(req.getHeaders()).thenReturn(headers);

        assertTrue(matcher.match(req, null));
    }

    @Test
    void match_headerWithValueMismatch_returnsFalse() {
        ParamOrHeaderMatcher matcher = new ParamOrHeaderMatcher(true,
                Collections.singletonList(NameValueExpressionSupport.build("X-Custom=value")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Custom", "other");
        when(req.getHeaders()).thenReturn(headers);

        assertFalse(matcher.match(req, null));
    }

    @Test
    void match_headerNegatedExists_returnsFalse() {
        ParamOrHeaderMatcher matcher = new ParamOrHeaderMatcher(true,
                Collections.singletonList(NameValueExpressionSupport.build("!X-Custom")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Custom", "value");
        when(req.getHeaders()).thenReturn(headers);

        assertFalse(matcher.match(req, null));
    }

    @Test
    void match_headerNegatedNotExists_returnsTrue() {
        ParamOrHeaderMatcher matcher = new ParamOrHeaderMatcher(true,
                Collections.singletonList(NameValueExpressionSupport.build("!X-Custom")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getHeaders()).thenReturn(new HttpHeaders());

        assertTrue(matcher.match(req, null));
    }

    @Test
    void match_multipleExpressions_anyMatchReturnsTrue() {
        ParamOrHeaderMatcher matcher = new ParamOrHeaderMatcher(false,
                Arrays.asList(
                        NameValueExpressionSupport.build("foo=nonexistent"),
                        NameValueExpressionSupport.build("bar=value")
                ));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getParameter("foo")).thenReturn("other");
        when(req.getParameter("bar")).thenReturn("value");

        assertTrue(matcher.match(req, null));
    }

    @Test
    void match_multipleExpressions_noneMatchReturnsFalse() {
        ParamOrHeaderMatcher matcher = new ParamOrHeaderMatcher(false,
                Arrays.asList(
                        NameValueExpressionSupport.build("foo=nonexistent"),
                        NameValueExpressionSupport.build("bar=value")
                ));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getParameter("foo")).thenReturn("other");
        when(req.getParameter("bar")).thenReturn("other");

        assertFalse(matcher.match(req, null));
    }

    @Test
    void isSameTypeMatcher_sameDirection_returnsTrue() {
        ParamOrHeaderMatcher m1 = new ParamOrHeaderMatcher(false, Collections.emptyList());
        ParamOrHeaderMatcher m2 = new ParamOrHeaderMatcher(false, Collections.emptyList());
        assertTrue(m1.isSameTypeMatcher(m2));
    }

    @Test
    void isSameTypeMatcher_differentDirection_returnsFalse() {
        ParamOrHeaderMatcher m1 = new ParamOrHeaderMatcher(false, Collections.emptyList());
        ParamOrHeaderMatcher m2 = new ParamOrHeaderMatcher(true, Collections.emptyList());
        assertFalse(m1.isSameTypeMatcher(m2));
    }

    @Test
    void isSameTypeMatcher_differentType_returnsFalse() {
        ParamOrHeaderMatcher m = new ParamOrHeaderMatcher(false, Collections.emptyList());
        assertFalse(m.isSameTypeMatcher(mock(Matcher.class)));
    }

    @Test
    void haveAmbiguous_bothNullValueSameNegated_returnsTrue() {
        ParamOrHeaderMatcher m1 = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("foo")));
        ParamOrHeaderMatcher m2 = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("foo")));
        assertTrue(m1.haveAmbiguous(m2));
    }

    @Test
    void haveAmbiguous_bothNullValueDifferentNegated_returnsFalse() {
        ParamOrHeaderMatcher m1 = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("foo")));
        ParamOrHeaderMatcher m2 = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("!foo")));
        assertFalse(m1.haveAmbiguous(m2));
    }

    @Test
    void haveAmbiguous_oneNullValueNotNegated_returnsTrue() {
        ParamOrHeaderMatcher m1 = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("foo")));
        ParamOrHeaderMatcher m2 = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("foo=bar")));
        assertTrue(m1.haveAmbiguous(m2));
    }

    @Test
    void haveAmbiguous_oneNullValueNegated_returnsFalse() {
        ParamOrHeaderMatcher m1 = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("!foo")));
        ParamOrHeaderMatcher m2 = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("foo=bar")));
        assertFalse(m1.haveAmbiguous(m2));
    }

    @Test
    void haveAmbiguous_sameValueSameNegated_returnsTrue() {
        ParamOrHeaderMatcher m1 = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("foo=bar")));
        ParamOrHeaderMatcher m2 = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("foo=bar")));
        assertTrue(m1.haveAmbiguous(m2));
    }

    @Test
    void haveAmbiguous_sameValueDifferentNegated_returnsFalse() {
        ParamOrHeaderMatcher m1 = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("foo=bar")));
        ParamOrHeaderMatcher m2 = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("foo!=bar")));
        assertFalse(m1.haveAmbiguous(m2));
    }

    @Test
    void haveAmbiguous_differentName_returnsFalse() {
        ParamOrHeaderMatcher m1 = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("foo=bar")));
        ParamOrHeaderMatcher m2 = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("baz=bar")));
        assertFalse(m1.haveAmbiguous(m2));
    }

    @Test
    void haveAmbiguous_differentDirection_returnsFalse() {
        ParamOrHeaderMatcher m1 = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("foo=bar")));
        ParamOrHeaderMatcher m2 = new ParamOrHeaderMatcher(true,
                Collections.singletonList(NameValueExpressionSupport.build("foo=bar")));
        assertFalse(m1.haveAmbiguous(m2));
    }

    @Test
    void haveAmbiguous_differentType_returnsFalse() {
        ParamOrHeaderMatcher m = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("foo")));
        assertFalse(m.haveAmbiguous(mock(Matcher.class)));
    }

    @Test
    void toString_params_returnsCorrectFormat() {
        ParamOrHeaderMatcher matcher = new ParamOrHeaderMatcher(false,
                Collections.singletonList(NameValueExpressionSupport.build("foo=bar")));
        assertEquals("params: foo=bar", matcher.toString());
    }

    @Test
    void toString_headers_returnsCorrectFormat() {
        ParamOrHeaderMatcher matcher = new ParamOrHeaderMatcher(true,
                Collections.singletonList(NameValueExpressionSupport.build("X-Custom")));
        assertEquals("headers: X-Custom", matcher.toString());
    }

    @Test
    void toString_multipleExpressions() {
        ParamOrHeaderMatcher matcher = new ParamOrHeaderMatcher(false,
                Arrays.asList(
                        NameValueExpressionSupport.build("foo=bar"),
                        NameValueExpressionSupport.build("baz")
                ));
        String str = matcher.toString();
        assertTrue(str.startsWith("params: "));
    }
}
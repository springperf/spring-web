package io.springperf.web.core.mapping.match;

import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsumeOrProduceMatcherTest {

    @Test
    void match_consumeMatchingContentType_returnsTrue() {
        ConsumeOrProduceMatcher matcher = new ConsumeOrProduceMatcher(false,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        when(req.getHeaders()).thenReturn(headers);

        assertTrue(matcher.match(req, null));
    }

    @Test
    void match_consumeMismatchContentType_returnsFalse() {
        ConsumeOrProduceMatcher matcher = new ConsumeOrProduceMatcher(false,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_XML);
        when(req.getHeaders()).thenReturn(headers);

        assertFalse(matcher.match(req, null));
    }

    @Test
    void match_consumeNegated_whenNotMatching_returnsTrue() {
        ConsumeOrProduceMatcher matcher = new ConsumeOrProduceMatcher(false,
                Collections.singletonList(MediaTypeExpressionSupport.build("!text/html")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        when(req.getHeaders()).thenReturn(headers);

        assertTrue(matcher.match(req, null));
    }

    @Test
    void match_consumeNegated_whenMatching_returnsFalse() {
        ConsumeOrProduceMatcher matcher = new ConsumeOrProduceMatcher(false,
                Collections.singletonList(MediaTypeExpressionSupport.build("!text/html")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        when(req.getHeaders()).thenReturn(headers);

        assertFalse(matcher.match(req, null));
    }

    @Test
    void match_consumeNoContentType_defaultsToOctetStream() {
        ConsumeOrProduceMatcher matcher = new ConsumeOrProduceMatcher(false,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/octet-stream")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        when(req.getHeaders()).thenReturn(headers);

        assertTrue(matcher.match(req, null));
    }

    @Test
    void match_produceMatchingAccept_returnsTrue() {
        ConsumeOrProduceMatcher matcher = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        when(req.getHeaders()).thenReturn(headers);

        assertTrue(matcher.match(req, null));
    }

    @Test
    void match_produceMismatchAccept_returnsFalse() {
        ConsumeOrProduceMatcher matcher = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.TEXT_XML));
        when(req.getHeaders()).thenReturn(headers);

        assertFalse(matcher.match(req, null));
    }

    @Test
    void match_produceAcceptAll_returnsTrue() {
        ConsumeOrProduceMatcher matcher = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.ALL));
        when(req.getHeaders()).thenReturn(headers);

        assertTrue(matcher.match(req, null));
    }

    @Test
    void match_produceEmptyAccept_defaultsToAll() {
        ConsumeOrProduceMatcher matcher = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json")));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.emptyList());
        when(req.getHeaders()).thenReturn(headers);

        // Empty accept defaults to ALL, which is compatible with application/json
        assertTrue(matcher.match(req, null));
    }

    @Test
    void getProducibleMediaTypes_consumeMatcher_returnsNull() {
        ConsumeOrProduceMatcher matcher = new ConsumeOrProduceMatcher(false,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json")));
        assertNull(matcher.getProducibleMediaTypes());
    }

    @Test
    void getProducibleMediaTypes_produceMatcher_returnsTypes() {
        ConsumeOrProduceMatcher matcher = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json")));
        assertNotNull(matcher.getProducibleMediaTypes());
        assertEquals(1, matcher.getProducibleMediaTypes().size());
        assertTrue(matcher.getProducibleMediaTypes().contains(MediaType.APPLICATION_JSON));
    }

    @Test
    void getProducibleMediaTypes_produceMatcherSkipsNegated() {
        ConsumeOrProduceMatcher matcher = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("!application/json")));
        assertNotNull(matcher.getProducibleMediaTypes());
        assertTrue(matcher.getProducibleMediaTypes().isEmpty());
    }

    @Test
    void isSameTypeMatcher_sameDirection_returnsTrue() {
        ConsumeOrProduceMatcher m1 = new ConsumeOrProduceMatcher(true, Collections.emptyList());
        ConsumeOrProduceMatcher m2 = new ConsumeOrProduceMatcher(true, Collections.emptyList());
        assertTrue(m1.isSameTypeMatcher(m2));
    }

    @Test
    void isSameTypeMatcher_differentDirection_returnsFalse() {
        ConsumeOrProduceMatcher m1 = new ConsumeOrProduceMatcher(true, Collections.emptyList());
        ConsumeOrProduceMatcher m2 = new ConsumeOrProduceMatcher(false, Collections.emptyList());
        assertFalse(m1.isSameTypeMatcher(m2));
    }

    @Test
    void isSameTypeMatcher_differentType_returnsFalse() {
        ConsumeOrProduceMatcher m = new ConsumeOrProduceMatcher(true, Collections.emptyList());
        assertFalse(m.isSameTypeMatcher(mock(Matcher.class)));
    }

    @Test
    void haveAmbiguous_sameTypeOverlappingMediaTypes_returnsTrue() {
        ConsumeOrProduceMatcher m1 = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/*")));
        ConsumeOrProduceMatcher m2 = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json")));
        assertTrue(m1.haveAmbiguous(m2));
    }

    @Test
    void haveAmbiguous_sameTypeNonOverlapping_returnsFalse() {
        ConsumeOrProduceMatcher m1 = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json")));
        ConsumeOrProduceMatcher m2 = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("text/html")));
        assertFalse(m1.haveAmbiguous(m2));
    }

    @Test
    void haveAmbiguous_differentDirection_returnsFalse() {
        ConsumeOrProduceMatcher m1 = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json")));
        ConsumeOrProduceMatcher m2 = new ConsumeOrProduceMatcher(false,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json")));
        assertFalse(m1.haveAmbiguous(m2));
    }

    @Test
    void haveAmbiguous_differentType_returnsFalse() {
        ConsumeOrProduceMatcher m = new ConsumeOrProduceMatcher(true, Collections.emptyList());
        assertFalse(m.haveAmbiguous(mock(Matcher.class)));
    }

    @Test
    void haveAmbiguous_sameNegatedAndOverlapping_returnsTrue() {
        ConsumeOrProduceMatcher m1 = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("!application/json")));
        ConsumeOrProduceMatcher m2 = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("!application/json")));
        assertTrue(m1.haveAmbiguous(m2));
    }

    @Test
    void haveAmbiguous_differentNegatedOverlapping_returnsFalse() {
        ConsumeOrProduceMatcher m1 = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json")));
        ConsumeOrProduceMatcher m2 = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("!application/json")));
        assertFalse(m1.haveAmbiguous(m2));
    }

    @Test
    void toString_consume_returnsCorrectFormat() {
        ConsumeOrProduceMatcher matcher = new ConsumeOrProduceMatcher(false,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json")));
        assertEquals("consumes: application/json", matcher.toString());
    }

    @Test
    void toString_produce_returnsCorrectFormat() {
        ConsumeOrProduceMatcher matcher = new ConsumeOrProduceMatcher(true,
                Collections.singletonList(MediaTypeExpressionSupport.build("application/json")));
        assertEquals("produces: application/json", matcher.toString());
    }

    @Test
    void toString_multipleMediaTypes() {
        ConsumeOrProduceMatcher matcher = new ConsumeOrProduceMatcher(true,
                Arrays.asList(
                        MediaTypeExpressionSupport.build("application/json"),
                        MediaTypeExpressionSupport.build("text/html")
                ));
        String str = matcher.toString();
        assertTrue(str.startsWith("produces: "));
    }
}

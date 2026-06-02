package io.springperf.web.core.mapping.match;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.*;

class MediaTypeExpressionSupportTest {

    @Test
    void build_simpleMediaType_parsesCorrectly() {
        MediaTypeExpressionSupport expr = MediaTypeExpressionSupport.build("application/json");
        assertEquals(MediaType.APPLICATION_JSON, expr.getMediaType());
        assertFalse(expr.isNegated());
    }

    @Test
    void build_negatedMediaType_parsesCorrectly() {
        MediaTypeExpressionSupport expr = MediaTypeExpressionSupport.build("!text/html");
        assertEquals(MediaType.TEXT_HTML, expr.getMediaType());
        assertTrue(expr.isNegated());
    }

    @Test
    void build_wildcardMediaType_parsesCorrectly() {
        MediaTypeExpressionSupport expr = MediaTypeExpressionSupport.build("*/*");
        assertEquals(MediaType.ALL, expr.getMediaType());
    }

    @Test
    void build_mediaTypeWithCharset_parsesCorrectly() {
        MediaTypeExpressionSupport expr = MediaTypeExpressionSupport.build("application/json;charset=UTF-8");
        assertEquals("UTF-8", expr.getMediaType().getParameter("charset"));
    }

    @Test
    void build_mediaTypeWithBoundary_parsesCorrectly() {
        MediaTypeExpressionSupport expr = MediaTypeExpressionSupport.build("multipart/form-data;boundary=----123");
        assertTrue(expr.getMediaType().isCompatibleWith(MediaType.MULTIPART_FORM_DATA));
    }

    @Test
    void toString_normal_returnsMediaTypeString() {
        MediaTypeExpressionSupport expr = MediaTypeExpressionSupport.build("application/json");
        assertEquals("application/json", expr.toString());
    }

    @Test
    void toString_negated_returnsExclamationPrefix() {
        MediaTypeExpressionSupport expr = MediaTypeExpressionSupport.build("!text/html");
        assertEquals("!text/html", expr.toString());
    }

    @Test
    void isNegated_default_returnsFalse() {
        MediaTypeExpressionSupport expr = MediaTypeExpressionSupport.build("application/json");
        assertFalse(expr.isNegated());
    }

    @Test
    void isNegated_withExclamation_returnsTrue() {
        MediaTypeExpressionSupport expr = MediaTypeExpressionSupport.build("!application/json");
        assertTrue(expr.isNegated());
    }
}

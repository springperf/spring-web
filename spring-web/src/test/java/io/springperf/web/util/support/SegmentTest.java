package io.springperf.web.util.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SegmentTest {

    @Test
    void parseRootPath_returnsEmptyList() {
        List<Segment> segments = Segment.parse("/");
        assertTrue(segments.isEmpty());
    }

    @Test
    void parseLiteralPath_returnsLiteralSegments() {
        List<Segment> segments = Segment.parse("/api/users");
        assertEquals(2, segments.size());
        assertEquals(SegKind.LITERAL, segments.get(0).kind);
        assertEquals("api", segments.get(0).literal);
        assertEquals(SegKind.LITERAL, segments.get(1).kind);
        assertEquals("users", segments.get(1).literal);
    }

    @Test
    void parseSingleWildcardVar_returnsSingleWildcard() {
        List<Segment> segments = Segment.parse("/api/{id}");
        assertEquals(2, segments.size());
        assertEquals(SegKind.LITERAL, segments.get(0).kind);
        assertEquals(SegKind.SINGLE_WILDCARD, segments.get(1).kind);
    }

    @Test
    void parseMultiWildcard_returnsMultiWildcard() {
        List<Segment> segments = Segment.parse("/api/**");
        assertEquals(2, segments.size());
        assertEquals(SegKind.LITERAL, segments.get(0).kind);
        assertEquals(SegKind.MULTI_WILDCARD, segments.get(1).kind);
    }

    @Test
    void parseAsterisk_returnsSingleWildcard() {
        List<Segment> segments = Segment.parse("/api/*");
        assertEquals(2, segments.size());
        assertEquals(SegKind.SINGLE_WILDCARD, segments.get(1).kind);
    }

    @Test
    void parseRegexVar_extractsPattern() {
        List<Segment> segments = Segment.parse("/api/{id:\\d+}");
        assertEquals(2, segments.size());
        assertEquals(SegKind.REGEX, segments.get(1).kind);
        assertNotNull(segments.get(1).regex);
        assertTrue(segments.get(1).regex.matcher("123").matches());
        assertFalse(segments.get(1).regex.matcher("abc").matches());
    }

    @Test
    void parseQuestionMarkWildcard_createsRegex() {
        List<Segment> segments = Segment.parse("/test/???");
        assertEquals(2, segments.size());
        assertEquals(SegKind.REGEX, segments.get(1).kind);
        assertTrue(segments.get(1).regex.matcher("abc").matches());
        assertFalse(segments.get(1).regex.matcher("ab").matches());
    }

    @Test
    void parseMixedPath_parsesCorrectly() {
        List<Segment> segments = Segment.parse("/a/{b}/c/**");
        assertEquals(4, segments.size());
        assertEquals(SegKind.LITERAL, segments.get(0).kind);
        assertEquals(SegKind.SINGLE_WILDCARD, segments.get(1).kind);
        assertEquals(SegKind.LITERAL, segments.get(2).kind);
        assertEquals(SegKind.MULTI_WILDCARD, segments.get(3).kind);
    }

    @Test
    void literal_factory_createsLiteral() {
        Segment seg = Segment.literal("test");
        assertEquals(SegKind.LITERAL, seg.kind);
        assertEquals("test", seg.literal);
    }

    @Test
    void regex_factory_createsRegex() {
        Segment seg = Segment.regex("{id:\\d+}", java.util.regex.Pattern.compile("\\d+"));
        assertEquals(SegKind.REGEX, seg.kind);
        assertNotNull(seg.regex);
    }

    @Test
    void constants_singletons() {
        assertSame(Segment.SINGLE_WILDCARD, Segment.SINGLE_WILDCARD);
        assertSame(Segment.MULTI_WILDCARD, Segment.MULTI_WILDCARD);
        assertEquals(SegKind.SINGLE_WILDCARD, Segment.SINGLE_WILDCARD.kind);
        assertEquals(SegKind.MULTI_WILDCARD, Segment.MULTI_WILDCARD.kind);
    }
}

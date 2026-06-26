package io.springperf.web.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WebUtilsTest {

    // ==================== normalize ====================

    @Test
    void normalize_null_returnsEmpty() {
        assertEquals("", WebUtils.normalize(null));
    }

    @Test
    void normalize_trimmed_returnsSame() {
        assertEquals("hello", WebUtils.normalize("hello"));
    }

    @Test
    void normalize_withSpaces_trims() {
        assertEquals("api", WebUtils.normalize("  api  "));
    }

    @Test
    void normalize_empty_returnsEmpty() {
        assertEquals("", WebUtils.normalize(""));
    }

    // ==================== formatPath ====================

    @Test
    void formatPath_withoutLeadingSlash_addsSlash() {
        assertEquals("/api", WebUtils.formatPath("api"));
    }

    @Test
    void formatPath_withLeadingSlash_keepsSlash() {
        assertEquals("/api", WebUtils.formatPath("/api"));
    }

    @Test
    void formatPath_withTrailingSlash_removesIt() {
        assertEquals("/api", WebUtils.formatPath("/api/"));
    }

    @Test
    void formatPath_root_returnsEmpty() {
        // formatPath("/") strips trailing slash → ""
        assertEquals("", WebUtils.formatPath("/"));
    }

    @Test
    void formatPath_withBothSlashes_formatsCorrectly() {
        assertEquals("/api/user", WebUtils.formatPath("api/user/"));
    }

    @Test
    void formatPath_empty_returnsEmpty() {
        // formatPath("") → adds leading / → "/" → strips trailing / → ""
        assertEquals("", WebUtils.formatPath(""));
    }

    // ==================== pathJoin ====================

    @Test
    void pathJoin_bothNormal_joinsWithSingleSlash() {
        assertEquals("/api/user", WebUtils.pathJoin("/api/", "/user/"));
    }

    @Test
    void pathJoin_withoutLeadingSlash() {
        assertEquals("/api/user", WebUtils.pathJoin("api", "user"));
    }

    @Test
    void pathJoin_firstNull_returnsSecond() {
        assertEquals("/test", WebUtils.pathJoin(null, "test/"));
    }

    @Test
    void pathJoin_secondNull_returnsFirst() {
        assertEquals("/api", WebUtils.pathJoin("/api", null));
    }

    @Test
    void pathJoin_bothNull_returnsRoot() {
        assertEquals("/", WebUtils.pathJoin(null, null));
    }

    @Test
    void pathJoin_bothEmpty_returnsRoot() {
        assertEquals("/", WebUtils.pathJoin("", ""));
    }

    @Test
    void pathJoin_firstEmpty_returnsSecond() {
        assertEquals("/user", WebUtils.pathJoin("", "/user/"));
    }

    @Test
    void pathJoin_secondEmpty_returnsFirst() {
        assertEquals("/api", WebUtils.pathJoin("/api/", ""));
    }

    @Test
    void pathJoin_withSpaces_trimsAndJoins() {
        assertEquals("/api/user", WebUtils.pathJoin("  /api/  ", "  /user/  "));
    }

    @Test
    void pathJoin_multiSegment_joinsCorrectly() {
        assertEquals("/api/v1/user", WebUtils.pathJoin("/api/v1", "/user"));
    }

    @Test
    void pathJoin_deepPath_joinsCorrectly() {
        assertEquals("/a/b/c/d", WebUtils.pathJoin("/a/b", "/c/d"));
    }

    // ==================== findAllSlashIndices ====================

    @Test
    void findAllSlashIndices_null_returnsEmpty() {
        assertArrayEquals(new int[0], WebUtils.findAllSlashIndices(null));
    }

    @Test
    void findAllSlashIndices_empty_returnsEmpty() {
        assertArrayEquals(new int[0], WebUtils.findAllSlashIndices(""));
    }

    @Test
    void findAllSlashIndices_noSlash_returnsEmpty() {
        assertArrayEquals(new int[0], WebUtils.findAllSlashIndices("abc"));
    }

    @Test
    void findAllSlashIndices_singleSlash() {
        assertArrayEquals(new int[]{0}, WebUtils.findAllSlashIndices("/abc"));
    }

    @Test
    void findAllSlashIndices_multipleSlashes() {
        assertArrayEquals(new int[]{0, 4, 7}, WebUtils.findAllSlashIndices("/api/v1/user"));
    }

    @Test
    void findAllSlashIndices_trailingSlash() {
        assertArrayEquals(new int[]{0, 4}, WebUtils.findAllSlashIndices("/api/"));
    }

    @Test
    void findAllSlashIndices_rootOnly() {
        assertArrayEquals(new int[]{0}, WebUtils.findAllSlashIndices("/"));
    }

    @Test
    void findAllSlashIndices_consecutiveSlashes() {
        assertArrayEquals(new int[]{0, 1, 2}, WebUtils.findAllSlashIndices("///"));
    }

    @Test
    void findAllSlashIndices_noLeadingSlash() {
        assertArrayEquals(new int[]{3, 5}, WebUtils.findAllSlashIndices("abc/d/e"));
    }
}
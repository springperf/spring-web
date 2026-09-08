package io.springperf.web.util;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MediaTypeUtilsTest {

    @Test
    void applicationStreamJson_constant() {
        assertEquals("application", MediaTypeUtils.APPLICATION_STREAM_JSON.getType());
        assertEquals("stream+json", MediaTypeUtils.APPLICATION_STREAM_JSON.getSubtype());
    }

    @Test
    void specificity_concreteBeforeWildcardType() {
        MediaType concrete = MediaType.parseMediaType("text/plain");
        MediaType wildcard = MediaType.parseMediaType("*/*");
        assertTrue(MediaTypeUtils.compareSpecificity(concrete, wildcard) < 0,
                "具体类型应排在通配类型之前");
    }

    @Test
    void specificity_concreteSubtypeBeforeWildcardSubtype() {
        MediaType concrete = MediaType.parseMediaType("text/plain");
        MediaType wildcard = MediaType.parseMediaType("text/*");
        assertTrue(MediaTypeUtils.compareSpecificity(concrete, wildcard) < 0,
                "具体子类型应排在通配子类型之前");
    }

    @Test
    void specificity_moreParametersFirst() {
        MediaType withParam = MediaType.parseMediaType("text/plain;charset=UTF-8");
        MediaType withoutParam = MediaType.parseMediaType("text/plain");
        assertTrue(MediaTypeUtils.compareSpecificity(withParam, withoutParam) < 0,
                "带参数的类型应更具体");
    }

    @Test
    void specificity_sameType_compareBySubtype() {
        MediaType a = MediaType.parseMediaType("application/json");
        MediaType b = MediaType.parseMediaType("application/xml");
        // 排序期望：json < xml（字母序）
        assertTrue(MediaTypeUtils.compareSpecificity(a, b) < 0);
    }

    @Test
    void sortBySpecificity_ordersDescending() {
        List<MediaType> list = new ArrayList<>();
        list.add(MediaType.parseMediaType("*/*"));
        list.add(MediaType.parseMediaType("text/*"));
        list.add(MediaType.parseMediaType("text/plain;charset=UTF-8"));
        list.add(MediaType.parseMediaType("text/plain"));

        MediaTypeUtils.sortBySpecificity(list);

        assertEquals("text/plain;charset=UTF-8", list.get(0).toString());
        assertEquals("text/plain", list.get(1).toString());
        assertEquals("text/*", list.get(2).toString());
        assertEquals("*/*", list.get(3).toString());
    }

    @Test
    void qualityComparator_higherQFirst() {
        MediaType q09 = MediaType.parseMediaType("text/plain;q=0.9");
        MediaType q10 = MediaType.parseMediaType("text/html");
        assertTrue(MediaTypeUtils.QUALITY_AND_SPECIFICITY_COMPARATOR.compare(q10, q09) < 0,
                "q=1 应排在 q=0.9 之前");
    }

    @Test
    void qualityComparator_equalQ_fallsBackToSpecificity() {
        MediaType q1 = MediaType.parseMediaType("*/*;q=1");
        MediaType q2 = MediaType.parseMediaType("text/plain;q=1");
        assertTrue(MediaTypeUtils.QUALITY_AND_SPECIFICITY_COMPARATOR.compare(q2, q1) < 0,
                "q 相同时更具体的类型在前");
    }

    @Test
    void compareSpecificity_equal_returnsZero() {
        MediaType a = MediaType.parseMediaType("text/plain");
        MediaType b = MediaType.parseMediaType("text/plain");
        assertEquals(0, MediaTypeUtils.compareSpecificity(a, b));
    }
}

package io.springperf.web.support.servlet.filter;

import javax.servlet.ServletContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class PerfFilterConfigTest {

    @Mock ServletContext servletContext;

    @Test
    void getFilterName() {
        PerfFilterConfig config = new PerfFilterConfig("testFilter", servletContext);
        assertEquals("testFilter", config.getFilterName());
    }

    @Test
    void getServletContext() {
        PerfFilterConfig config = new PerfFilterConfig("testFilter", servletContext);
        assertSame(servletContext, config.getServletContext());
    }

    @Test
    void getInitParameter() {
        Map<String, String> params = new HashMap<>();
        params.put("key1", "value1");
        PerfFilterConfig config = new PerfFilterConfig("testFilter", servletContext, params);
        assertEquals("value1", config.getInitParameter("key1"));
    }

    @Test
    void getInitParameter_missing_returnsNull() {
        PerfFilterConfig config = new PerfFilterConfig("testFilter", servletContext);
        assertNull(config.getInitParameter("nonexistent"));
    }

    @Test
    void getInitParameterNames() {
        Map<String, String> params = new HashMap<>();
        params.put("key1", "value1");
        params.put("key2", "value2");
        PerfFilterConfig config = new PerfFilterConfig("testFilter", servletContext, params);
        java.util.List<String> names = new java.util.ArrayList<>();
        java.util.Collections.list(config.getInitParameterNames()).forEach(names::add);
        assertEquals(2, names.size());
        assertTrue(names.contains("key1"));
        assertTrue(names.contains("key2"));
    }

    @Test
    void getInitParameterNames_empty() {
        PerfFilterConfig config = new PerfFilterConfig("testFilter", servletContext);
        assertFalse(config.getInitParameterNames().hasMoreElements());
    }

    @Test
    void nullInitParameters_usesEmptyMap() {
        PerfFilterConfig config = new PerfFilterConfig("testFilter", servletContext, null);
        assertNull(config.getInitParameter("any"));
    }
}
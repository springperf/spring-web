package io.springperf.web.json;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FastjsonConverterTest {

    static class TestBean {
        public String name;
        public int value;

        public TestBean() {}

        public TestBean(String name, int value) {
            this.name = name;
            this.value = value;
        }
    }

    @Test
    void toJson_pojo_returnsValidJson() {
        FastjsonConverter converter = new FastjsonConverter();
        TestBean bean = new TestBean("test", 42);
        String json = converter.toJson(bean);
        assertTrue(json.contains("\"name\""));
        assertTrue(json.contains("\"test\""));
        assertTrue(json.contains("\"value\""));
        assertTrue(json.contains("42"));
    }

    @Test
    void toJson_map_returnsValidJson() {
        FastjsonConverter converter = new FastjsonConverter();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", "value");
        map.put("num", 123);
        String json = converter.toJson(map);
        assertTrue(json.contains("\"key\""));
        assertTrue(json.contains("\"num\""));
    }

    @Test
    void toJson_null_returnsNullString() {
        FastjsonConverter converter = new FastjsonConverter();
        assertEquals("null", converter.toJson(null));
    }

    @Test
    void toJson_outputStream_writesToStream() {
        FastjsonConverter converter = new FastjsonConverter();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        converter.toJson(baos, Collections.singletonMap("msg", "hello"));
        String json = new String(baos.toByteArray());
        assertTrue(json.contains("\"msg\""));
        assertTrue(json.contains("\"hello\""));
    }

    @Test
    void fromJson_string_pojo() {
        FastjsonConverter converter = new FastjsonConverter();
        TestBean result = (TestBean) converter.fromJson("{\"name\":\"test\",\"value\":42}", TestBean.class);
        assertEquals("test", result.name);
        assertEquals(42, result.value);
    }

    @Test
    void fromJson_string_list() {
        FastjsonConverter converter = new FastjsonConverter();
        List<?> result = (List<?>) converter.fromJson("[1,2,3]", List.class);
        assertEquals(3, result.size());
    }

    @Test
    void fromJson_string_map() {
        FastjsonConverter converter = new FastjsonConverter();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) converter.fromJson(
                "{\"a\":1,\"b\":\"two\"}", Map.class);
        assertEquals(1, result.get("a"));
        assertEquals("two", result.get("b"));
    }

    @Test
    void fromJson_bytes_pojo() {
        FastjsonConverter converter = new FastjsonConverter();
        byte[] json = "{\"name\":\"bytes\",\"value\":99}".getBytes();
        TestBean result = (TestBean) converter.fromJson(json, TestBean.class);
        assertEquals("bytes", result.name);
        assertEquals(99, result.value);
    }

    @Test
    void fromJson_bytes_empty() {
        FastjsonConverter converter = new FastjsonConverter();
        byte[] json = "{}".getBytes();
        TestBean result = (TestBean) converter.fromJson(json, TestBean.class);
        assertNull(result.name);
        assertEquals(0, result.value);
    }

    @Test
    void fromJson_inputStream_pojo() {
        FastjsonConverter converter = new FastjsonConverter();
        String jsonStr = "{\"name\":\"stream\",\"value\":77}";
        InputStream is = new ByteArrayInputStream(jsonStr.getBytes());
        TestBean result = (TestBean) converter.fromJson(is, TestBean.class);
        assertEquals("stream", result.name);
        assertEquals(77, result.value);
    }

    @Test
    void fromJson_inputStream_largeData() {
        FastjsonConverter converter = new FastjsonConverter();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(i).append("}");
        }
        sb.append("]");
        InputStream is = new ByteArrayInputStream(sb.toString().getBytes());
        List<?> result = (List<?>) converter.fromJson(is, List.class);
        assertEquals(100, result.size());
    }

    @Test
    void roundTrip_pojo() {
        FastjsonConverter converter = new FastjsonConverter();
        String json = converter.toJson(new TestBean("round", 555));
        TestBean result = (TestBean) converter.fromJson(json, TestBean.class);
        assertEquals("round", result.name);
        assertEquals(555, result.value);
    }

    @Test
    void getComponentName_returnsClassName() {
        FastjsonConverter converter = new FastjsonConverter();
        assertEquals("FastjsonConverter", converter.getComponentName());
    }

    @Test
    void toJson_specialCharacters() {
        FastjsonConverter converter = new FastjsonConverter();
        Map<String, String> map = new LinkedHashMap<>();
        map.put("msg", "hello \"world\" & <test>");
        String json = converter.toJson(map);
        assertTrue(json.contains("\\\""));
    }

    @Test
    void toJson_outputStream_nullObject_writesNull() {
        FastjsonConverter converter = new FastjsonConverter();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        converter.toJson(baos, null);
        assertEquals("null", new String(baos.toByteArray()));
    }
}
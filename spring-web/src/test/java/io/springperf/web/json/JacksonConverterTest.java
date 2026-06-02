package io.springperf.web.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class JacksonConverterTest {

    static class TestBean {
        public String name;
        public int value;

        public TestBean() {}

        public TestBean(String name, int value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TestBean bean = (TestBean) o;
            return value == bean.value && Objects.equals(name, bean.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, value);
        }
    }

    @Test
    void defaultConstructor_createsMapper() {
        JacksonConverter converter = new JacksonConverter();
        assertNotNull(converter);
    }

    @Test
    void constructor_withMapper_usesCustomMapper() {
        ObjectMapper customMapper = new ObjectMapper();
        JacksonConverter converter = new JacksonConverter(customMapper);
        assertNotNull(converter);
    }

    @Test
    void toJson_pojo_returnsValidJson() {
        JacksonConverter converter = new JacksonConverter();
        TestBean bean = new TestBean("test", 42);
        String json = converter.toJson(bean);
        assertTrue(json.contains("\"name\""));
        assertTrue(json.contains("\"test\""));
        assertTrue(json.contains("\"value\""));
        assertTrue(json.contains("42"));
    }

    @Test
    void toJson_map_returnsValidJson() {
        JacksonConverter converter = new JacksonConverter();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", "value");
        map.put("num", 123);
        String json = converter.toJson(map);
        assertEquals("{\"key\":\"value\",\"num\":123}", json);
    }

    @Test
    void toJson_null_returnsNull() {
        JacksonConverter converter = new JacksonConverter();
        // Jackson writes null as "null"
        assertEquals("null", converter.toJson(null));
    }

    @Test
    void toJson_outputStream_writesToStream() {
        JacksonConverter converter = new JacksonConverter();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Map<String, String> map = Collections.singletonMap("msg", "hello");
        converter.toJson(baos, map);
        String json = new String(baos.toByteArray());
        assertTrue(json.contains("\"msg\""));
        assertTrue(json.contains("\"hello\""));
    }

    @Test
    void toJson_outputStream_emptyObject_writesBraces() {
        JacksonConverter converter = new JacksonConverter();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        converter.toJson(baos, Collections.emptyMap());
        assertEquals("{}", new String(baos.toByteArray()));
    }

    @Test
    void fromJson_string_pojo() {
        JacksonConverter converter = new JacksonConverter();
        TestBean result = (TestBean) converter.fromJson("{\"name\":\"test\",\"value\":42}", TestBean.class);
        assertEquals("test", result.name);
        assertEquals(42, result.value);
    }

    @Test
    void fromJson_string_list() {
        JacksonConverter converter = new JacksonConverter();
        List<?> result = (List<?>) converter.fromJson("[1,2,3]", List.class);
        assertEquals(3, result.size());
    }

    @Test
    void fromJson_string_map() {
        JacksonConverter converter = new JacksonConverter();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) converter.fromJson(
                "{\"a\":1,\"b\":\"two\"}", Map.class);
        assertEquals(1, result.get("a"));
        assertEquals("two", result.get("b"));
    }

    @Test
    void fromJson_bytes_pojo() {
        JacksonConverter converter = new JacksonConverter();
        byte[] json = "{\"name\":\"bytes\",\"value\":99}".getBytes();
        TestBean result = (TestBean) converter.fromJson(json, TestBean.class);
        assertEquals("bytes", result.name);
        assertEquals(99, result.value);
    }

    @Test
    void fromJson_bytes_empty() {
        JacksonConverter converter = new JacksonConverter();
        byte[] json = "{}".getBytes();
        TestBean result = (TestBean) converter.fromJson(json, TestBean.class);
        assertNull(result.name);
        assertEquals(0, result.value);
    }

    @Test
    void fromJson_inputStream_pojo() {
        JacksonConverter converter = new JacksonConverter();
        String jsonStr = "{\"name\":\"stream\",\"value\":77}";
        InputStream is = new ByteArrayInputStream(jsonStr.getBytes());
        TestBean result = (TestBean) converter.fromJson(is, TestBean.class);
        assertEquals("stream", result.name);
        assertEquals(77, result.value);
    }

    @Test
    void fromJson_inputStream_largeData() {
        JacksonConverter converter = new JacksonConverter();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":").append(i).append(",\"val\":\"v").append(i).append("\"}");
        }
        sb.append("]");
        InputStream is = new ByteArrayInputStream(sb.toString().getBytes());
        List<?> result = (List<?>) converter.fromJson(is, List.class);
        assertEquals(100, result.size());
    }

    @Test
    void fromJson_invalidJson_throwsException() {
        JacksonConverter converter = new JacksonConverter();
        assertThrows(Exception.class, () ->
                converter.fromJson("{invalid}", Map.class));
    }

    @Test
    void roundTrip_pojo() {
        JacksonConverter converter = new JacksonConverter();
        TestBean original = new TestBean("round", 555);
        String json = converter.toJson(original);
        TestBean result = (TestBean) converter.fromJson(json, TestBean.class);
        assertEquals(original.name, result.name);
        assertEquals(original.value, result.value);
    }

    @Test
    void roundTrip_listOfMaps() {
        JacksonConverter converter = new JacksonConverter();
        List<Map<String, Object>> original = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("x", 10);
        item.put("y", 20);
        original.add(item);

        String json = converter.toJson(original);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) converter.fromJson(json, List.class);
        assertEquals(1, result.size());
        assertEquals(10, result.get(0).get("x"));
    }

    @Test
    void getComponentName_returnsClassName() {
        JacksonConverter converter = new JacksonConverter();
        assertEquals("JacksonConverter", converter.getComponentName());
    }

    @Test
    void toJson_specialCharacters() {
        JacksonConverter converter = new JacksonConverter();
        Map<String, String> map = new LinkedHashMap<>();
        map.put("msg", "hello \"world\" & <test>");
        String json = converter.toJson(map);
        assertTrue(json.contains("\\\""));
    }

    @Test
    void toJson_outputStream_nullObject_writesNull() {
        JacksonConverter converter = new JacksonConverter();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        converter.toJson(baos, null);
        assertEquals("null", new String(baos.toByteArray()));
    }
}
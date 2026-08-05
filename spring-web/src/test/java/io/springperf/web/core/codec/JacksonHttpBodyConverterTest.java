package io.springperf.web.core.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.lang.reflect.Type;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link JacksonHttpBodyConverter#isJsonMediaType} 的等价行为：
 * 仅接受 JSON 相关 MediaType（application/json、application/*、application/*+json、通配类型、null）。
 */
class JacksonHttpBodyConverterTest {

    static class Payload {
        public String name;

        Payload() {
            this.name = "x";
        }
    }

    JacksonHttpBodyConverter converter;

    @BeforeEach
    void setUp() {
        converter = new JacksonHttpBodyConverter(new ObjectMapper());
    }

    // ---- canRead : mediaType 判断 ----

    @Test
    void canRead_applicationJson() {
        assertTrue(converter.canRead((Type) Object.class, null, MediaType.APPLICATION_JSON, null, null));
    }

    @Test
    void canRead_nullMediaType() {
        assertTrue(converter.canRead((Type) Object.class, null, null, null, null));
    }

    @Test
    void canRead_wildcardType() {
        assertTrue(converter.canRead((Type) Object.class, null, MediaType.ALL, null, null));
    }

    @Test
    void canRead_applicationWildcardSubtype() {
        assertTrue(converter.canRead((Type) Object.class, null, new MediaType("application", "*"), null, null));
    }

    @Test
    void canRead_applicationSuffixWildcardJson() {
        assertTrue(converter.canRead((Type) Object.class, null, new MediaType("application", "*+json"), null, null));
    }

    @Test
    void canRead_jsonWithCharset() {
        assertTrue(converter.canRead((Type) Object.class, null,
                new MediaType("application", "json", java.util.Collections.singletonMap("charset", "utf-8")), null, null));
    }

    @Test
    void canRead_nonJsonMediaType_rejected() {
        assertFalse(converter.canRead((Type) Object.class, null, MediaType.APPLICATION_XML, null, null));
        assertFalse(converter.canRead((Type) Object.class, null, MediaType.TEXT_PLAIN, null, null));
        assertFalse(converter.canRead((Type) Object.class, null, MediaType.APPLICATION_OCTET_STREAM, null, null));
        assertFalse(converter.canRead((Type) Object.class, null, new MediaType("application", "*+xml"), null, null));
    }

    @Test
    void canRead_stringType_rejected() {
        // String 由 StringHttpMessageConverter 处理，而非 Jackson
        assertFalse(converter.canRead((Type) String.class, null, MediaType.APPLICATION_JSON, null, null));
    }

    // ---- canWrite : mediaType 判断 ----

    @Test
    void canWrite_applicationJson() {
        assertTrue(converter.canWrite((Type) Payload.class, Payload.class, MediaType.APPLICATION_JSON, null, null, null));
    }

    @Test
    void canWrite_nullMediaType() {
        assertTrue(converter.canWrite((Type) Payload.class, Payload.class, null, null, null, null));
    }

    @Test
    void canWrite_wildcardType() {
        assertTrue(converter.canWrite((Type) Payload.class, Payload.class, MediaType.ALL, null, null, null));
    }

    @Test
    void canWrite_applicationSuffixWildcardJson() {
        assertTrue(converter.canWrite((Type) Payload.class, Payload.class, new MediaType("application", "*+json"), null, null, null));
    }

    @Test
    void canWrite_nonJsonMediaType_rejected() {
        assertFalse(converter.canWrite((Type) Payload.class, Payload.class, MediaType.APPLICATION_XML, null, null, null));
        assertFalse(converter.canWrite((Type) Payload.class, Payload.class, MediaType.APPLICATION_OCTET_STREAM, null, null, null));
    }
}

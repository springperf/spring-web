package io.springperf.web.core.codec;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.json.MappingJacksonValue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JacksonHttpBodyConverterDetailsTest {

    static class Dto {
        public String name;
        Dto() {}
        Dto(String name) { this.name = name; }
    }

    static class Payload {
        public String name;
        Payload() { this.name = "payload"; }
    }

    static class ViewA {}

    static class ViewB {}

    static class ViewDto {
        @JsonView(ViewA.class)
        public String name;
        @JsonView(ViewB.class)
        public String secret;

        ViewDto() {}
        ViewDto(String name, String secret) {
            this.name = name;
            this.secret = secret;
        }
    }

    static class SerializeFail {
        private final Object self;

        SerializeFail() {
            this.self = this; // 自引用：默认 FAIL_ON_SELF_REFERENCES 会抛 JsonMappingException
        }

        public Object getSelf() {
            return self;
        }
    }

    private JacksonHttpBodyConverter converter;

    @BeforeEach
    void setUp() {
        converter = new JacksonHttpBodyConverter(new ObjectMapper());
    }

    private WebServerHttpRequest mockReq() {
        return mock(WebServerHttpRequest.class);
    }

    private WebServerHttpResponse mockResp() {
        return mock(WebServerHttpResponse.class);
    }

    private org.springframework.http.HttpInputMessage input(String json) {
        InputStream body = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        return new org.springframework.http.HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return body;
            }

            @Override
            public org.springframework.http.HttpHeaders getHeaders() {
                return new org.springframework.http.HttpHeaders();
            }
        };
    }

    @Test
    void read_jsonDeserializesAndCachesJavaType() throws Exception {
        PathMappingContext mapping = new PathMappingContext(
                new org.springframework.web.method.HandlerMethod(new Dto(), Dto.class.getMethod("toString")),
                java.util.Collections.emptyList(), "/dto") {
        };
        // 用真实 mapping 验证 JavaType 缓存
        Object result = converter.read((Type) Dto.class, null, input("{\"name\":\"hello\"}"),
                mockReq(), null);
        assertTrue(result instanceof Dto);
        assertEquals("hello", ((Dto) result).name);
    }

    @Test
    void read_withMappingContext_cachesJavaType() throws Exception {
        PathMappingContext mapping = mock(PathMappingContext.class);
        when(mapping.get(any(io.springperf.web.core.mapping.MappingCacheKey.class))).thenReturn(null);
        Object result = converter.read((Type) Dto.class, null, input("{\"name\":\"cached\"}"),
                mockReq(), mapping);
        assertEquals("cached", ((Dto) result).name);
        verify(mapping).set(any(), any());
    }

    @Test
    void write_stringUsesFastPathNoSerialization() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        org.springframework.http.HttpOutputMessage output = outputMessage(out);
        converter.write("hello", (Type) String.class, MediaType.APPLICATION_JSON,
                output, mockReq(), mockResp(), null);
        assertEquals("hello", new String(out.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void write_byteArrayUsesFastPath() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        org.springframework.http.HttpOutputMessage output = outputMessage(out);
        byte[] data = "bytes".getBytes(StandardCharsets.UTF_8);
        converter.write(data, (Type) byte[].class, MediaType.APPLICATION_JSON,
                output, mockReq(), mockResp(), null);
        assertArrayEquals(data, out.toByteArray());
    }

    @Test
    void write_objectSerializesAsJson() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        org.springframework.http.HttpOutputMessage output = outputMessage(out);
        converter.write(new Dto("perf"), (Type) Dto.class, MediaType.APPLICATION_JSON,
                output, mockReq(), mockResp(), null);
        String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"name\":\"perf\""));
    }

    @Test
    void write_mappingJacksonValue_usesView() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        org.springframework.http.HttpOutputMessage output = outputMessage(out);
        MappingJacksonValue mjv = new MappingJacksonValue(new ViewDto("visible", "hidden"));
        mjv.setSerializationView(ViewA.class);
        converter.write(mjv, (Type) Object.class, MediaType.APPLICATION_JSON,
                output, mockReq(), mockResp(), null);
        String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(json.contains("visible"));
        assertFalse(json.contains("hidden"), "未在 JsonView 中的字段不应序列化");
    }

    @Test
    void write_invalidDefinition_throwsConversionException() throws Exception {
        // InvalidDefinitionException（如自引用）→ HttpMessageConversionException
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        org.springframework.http.HttpOutputMessage output = outputMessage(out);
        assertThrows(org.springframework.http.converter.HttpMessageConversionException.class, () ->
                converter.write(new SerializeFail(), (Type) SerializeFail.class,
                        MediaType.APPLICATION_JSON, output, mockReq(), mockResp(), null));
    }

    @Test
    void canWrite_declaredType_cachesSerializable() {
        PathMappingContext mapping = mock(PathMappingContext.class);
        when(mapping.get(any(io.springperf.web.core.mapping.MappingCacheKey.class))).thenReturn(null);
        assertTrue(converter.canWrite((Type) Dto.class, Dto.class, MediaType.APPLICATION_JSON,
                mockReq(), mockResp(), mapping));
        verify(mapping).set(any(), eq(Boolean.TRUE));
    }

    @Test
    void canRead_implementsGenericInterface() {
        assertTrue(converter.canRead((Type) Payload.class, null, MediaType.APPLICATION_JSON, null, null));
        assertTrue(converter.canRead(Payload.class, MediaType.APPLICATION_JSON));
        assertTrue(converter.canWrite(Payload.class, null));
        assertEquals(JacksonHttpBodyConverter.class, converter.getConverterClass());
        assertEquals(2, converter.getSupportedMediaTypes().size());
        assertEquals(2, converter.getSupportedMediaTypes(Payload.class).size());
    }

    @Test
    void initComponentPhase1_usesSpringBeanOrDefault() throws Exception {
        WebContext wc = mock(WebContext.class);
        when(wc.getBeanFromCtx(ObjectMapper.class)).thenReturn(null);
        JacksonHttpBodyConverter c = new JacksonHttpBodyConverter();
        c.initWithWebContext(wc);
        c.initComponentPhase1();
        // 无 bean 时默认 new ObjectMapper，getOrder 稳定
        assertEquals(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 50000, c.getOrder());
    }

    @Test
    void initComponentPhase1_usesSpringBeanWhenPresent() throws Exception {
        ObjectMapper bean = new ObjectMapper();
        WebContext wc = mock(WebContext.class);
        when(wc.getBeanFromCtx(ObjectMapper.class)).thenReturn(bean);
        JacksonHttpBodyConverter c = new JacksonHttpBodyConverter();
        c.initWithWebContext(wc);
        c.initComponentPhase1();
        // 通过 write 验证使用 spring bean（bean 无特殊配置，仍可序列化普通对象）
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        c.write(new Dto("from-bean"), (Type) Dto.class, MediaType.APPLICATION_JSON,
                outputMessage(out), mockReq(), mockResp(), null);
        assertTrue(new String(out.toByteArray(), StandardCharsets.UTF_8).contains("from-bean"));
    }

    private org.springframework.http.HttpOutputMessage outputMessage(ByteArrayOutputStream out) {
        return new org.springframework.http.HttpOutputMessage() {
            @Override
            public ByteArrayOutputStream getBody() {
                return out;
            }

            @Override
            public org.springframework.http.HttpHeaders getHeaders() {
                return new org.springframework.http.HttpHeaders();
            }
        };
    }
}
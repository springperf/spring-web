package io.springperf.web.core.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.json.MappingJacksonValue;
import org.springframework.web.method.HandlerMethod;

import com.fasterxml.jackson.annotation.JsonFilter;
import com.fasterxml.jackson.annotation.JsonView;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.SimpleBeanPropertyFilter;
import tools.jackson.databind.ser.std.SimpleFilterProvider;
import tools.jackson.databind.ser.std.StdSerializer;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

class JacksonHttpBodyConverterCoverageTest {

    static class ViewA {
    }

    static class ViewB {
    }

    static class ViewDto {
        @JsonView(ViewA.class)
        public String name;
        @JsonView(ViewB.class)
        public String secret;

        ViewDto() {
        }

        ViewDto(String name, String secret) {
            this.name = name;
            this.secret = secret;
        }
    }

    @JsonFilter("secretFilter")
    static class FilteredDto {
        public String visible;
        public String secret;

        FilteredDto() {
        }

        FilteredDto(String visible, String secret) {
            this.visible = visible;
            this.secret = secret;
        }
    }

    static class ViewController {
        @JsonView(ViewA.class)
        public ViewDto get() {
            return new ViewDto("visible", "hidden");
        }

        public ViewDto plain() {
            return new ViewDto("v", "h");
        }
    }

    static class Boom {
        public int x = 1;
    }

    /** 自引用循环，用于制造真实的序列化失败（Jackson 默认不允许循环引用）。 */
    static class SelfRef {
        public SelfRef self;
    }

    private final JacksonHttpBodyConverter converter = new JacksonHttpBodyConverter(new ObjectMapper());

    @Test
    void deprecatedDelegates_routeThroughNewApi() throws Exception {
        assertTrue(converter.canRead((Type) ViewDto.class, null, MediaType.APPLICATION_JSON));
        assertTrue(converter.canWrite((Type) ViewDto.class, ViewDto.class, MediaType.APPLICATION_JSON));

        Object readByType = converter.read((Type) ViewDto.class, null, input("{\"name\":\"a\"}"));
        assertEquals("a", ((ViewDto) readByType).name);
        Object readByClass = converter.read(ViewDto.class, input("{\"name\":\"b\"}"));
        assertEquals("b", ((ViewDto) readByClass).name);

        ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        converter.write(new ViewDto("w", "s"), (Type) ViewDto.class, MediaType.APPLICATION_JSON, outputMessage(out1));
        assertTrue(new String(out1.toByteArray(), StandardCharsets.UTF_8).contains("\"name\":\"w\""));

        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        String chinese = "\u4e2d\u6587";
        converter.write(chinese, MediaType.APPLICATION_JSON, outputMessage(out2));
        assertArrayEquals(chinese.getBytes(StandardCharsets.UTF_8), out2.toByteArray());
    }

    @Test
    void read_withCachedJavaType_inMappingContext() throws Exception {
        PathMappingContext mapping = mock(PathMappingContext.class);
        JavaType javaType = new ObjectMapper().getTypeFactory().constructType(ViewDto.class);
        when(mapping.get(any(io.springperf.web.core.mapping.MappingCacheKey.class))).thenReturn(javaType);

        Object read = converter.read((Type) ViewDto.class, null, input("{\"name\":\"cached-name\"}"),
                mock(WebServerHttpRequest.class), mapping);

        assertEquals("cached-name", ((ViewDto) read).name);
        verify(mapping, atLeastOnce()).get(any(io.springperf.web.core.mapping.MappingCacheKey.class));
    }



    @Test
    void write_unserializableValue_mapsToNotWritable() {
        // Jackson 3 里 JacksonException 的构造器是 protected、且需要 JsonParser/JsonGenerator，
        // 测试无法直接 new。改为制造一次真实的序列化失败：自引用循环对象会触发
        // DatabindException（JacksonException 子类），由转换器映射为 HttpMessageNotWritableException。
        SelfRef selfRef = new SelfRef();
        selfRef.self = selfRef;

        // 循环引用触发 InvalidDefinitionException（DatabindException 的子类），
        // 转换器把它映射为 HttpMessageConversionException（同 Spring 的行为）。
        HttpMessageConversionException ex = assertThrows(HttpMessageConversionException.class,
                () -> converter.write(selfRef, (Type) SelfRef.class, MediaType.APPLICATION_JSON,
                        outputMessage(new ByteArrayOutputStream()), mock(WebServerHttpRequest.class),
                        mock(WebServerHttpResponse.class), null));
        assertNotNull(ex.getMessage());
    }

    @Test
    void write_withJsonViewMappingContext_usesViewAndCaches() throws Exception {
        PathMappingContext mapping = realMapping("get");

        ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        converter.write(new ViewDto("visible", "hidden"), (Type) ViewDto.class, MediaType.APPLICATION_JSON,
                outputMessage(out1), mock(WebServerHttpRequest.class), mock(WebServerHttpResponse.class), mapping);
        String json1 = new String(out1.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(json1.contains("visible"));
        assertFalse(json1.contains("hidden"));

        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        converter.write(new ViewDto("visible2", "hidden2"), (Type) ViewDto.class, MediaType.APPLICATION_JSON,
                outputMessage(out2), mock(WebServerHttpRequest.class), mock(WebServerHttpResponse.class), mapping);
        assertTrue(new String(out2.toByteArray(), StandardCharsets.UTF_8).contains("visible2"));
    }

    @Test
    void write_withPlainMappingContext_usesPlainWriter() throws Exception {
        PathMappingContext mapping = realMapping("plain");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        converter.write(new ViewDto("v", "h"), (Type) ViewDto.class, MediaType.APPLICATION_JSON, outputMessage(out),
                mock(WebServerHttpRequest.class), mock(WebServerHttpResponse.class), mapping);

        String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"name\":\"v\""));
        assertTrue(json.contains("\"secret\":\"h\""));
    }

    private static PathMappingContext realMapping(String methodName) throws Exception {
        Method method = ViewController.class.getMethod(methodName);
        return new PathMappingContext(new HandlerMethod(new ViewController(), method), Collections.emptyList(), "/v");
    }

    private static HttpInputMessage input(String json) {
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }
        };
    }

    private static HttpOutputMessage outputMessage(ByteArrayOutputStream out) {
        return new HttpOutputMessage() {
            @Override
            public ByteArrayOutputStream getBody() {
                return out;
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }
        };
    }
}

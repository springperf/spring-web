package io.springperf.web.core.codec;

import com.fasterxml.jackson.annotation.JsonFilter;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.json.MappingJacksonValue;
import org.springframework.web.method.HandlerMethod;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JacksonHttpBodyConverterCoverageTest {

    static class ViewA {}

    static class ViewB {}

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
    void canWrite_cachedSerializableType_shortCircuits() {
        PathMappingContext mapping = mock(PathMappingContext.class);
        when(mapping.get(any(io.springperf.web.core.mapping.MappingCacheKey.class))).thenReturn(Boolean.TRUE);

        assertTrue(converter.canWrite((Type) ViewDto.class, ViewDto.class, MediaType.APPLICATION_JSON,
                mock(WebServerHttpRequest.class), mock(WebServerHttpResponse.class), mapping));
    }

    @Test
    void write_mappingJacksonValueWithFilters_appliesFilter() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MappingJacksonValue mjv = new MappingJacksonValue(new FilteredDto("show", "hide"));
        SimpleFilterProvider filters = new SimpleFilterProvider()
                .addFilter("secretFilter", SimpleBeanPropertyFilter.serializeAllExcept("secret"));
        mjv.setFilters(filters);

        converter.write(mjv, (Type) FilteredDto.class, MediaType.APPLICATION_JSON, outputMessage(out),
                mock(WebServerHttpRequest.class), mock(WebServerHttpResponse.class), null);

        String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(json.contains("show"));
        assertFalse(json.contains("hide"));
    }

    @Test
    void write_jsonProcessingException_mapsToNotWritable() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Boom.class, new StdSerializer<>(Boom.class) {
            @Override
            public void serialize(Boom value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                throw new JsonProcessingException("boom") {
                };
            }
        });
        JacksonHttpBodyConverter failingConverter = new JacksonHttpBodyConverter(new ObjectMapper().registerModule(module));

        HttpMessageNotWritableException ex = assertThrows(HttpMessageNotWritableException.class, () ->
                failingConverter.write(new Boom(), (Type) Boom.class, MediaType.APPLICATION_JSON,
                        outputMessage(new ByteArrayOutputStream()),
                        mock(WebServerHttpRequest.class), mock(WebServerHttpResponse.class), null));
        assertTrue(ex.getMessage().contains("boom"));
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
        converter.write(new ViewDto("v", "h"), (Type) ViewDto.class, MediaType.APPLICATION_JSON,
                outputMessage(out), mock(WebServerHttpRequest.class), mock(WebServerHttpResponse.class), mapping);

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
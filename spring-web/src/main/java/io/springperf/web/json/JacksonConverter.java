package io.springperf.web.json;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;

public class JacksonConverter implements JsonConverter {

    private ObjectMapper mapper;

    public JacksonConverter() {
        mapper = new ObjectMapper();
    }

    public JacksonConverter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @SneakyThrows
    @Override
    public String toJson(Object obj) {
        return mapper.writeValueAsString(obj);
    }

    @Override
    @SneakyThrows
    public void toJson(OutputStream outputStream, Object obj) {
        mapper.writeValue(outputStream, obj);
    }

    @SneakyThrows
    @Override
    public Object fromJson(String json, Type type) {
        JavaType javaType = mapper.getTypeFactory().constructType(type);
        return mapper.readValue(json, javaType);
    }

    @SneakyThrows
    @Override
    public Object fromJson(byte[] json, Type type) {
        JavaType javaType = mapper.getTypeFactory().constructType(type);
        return mapper.readValue(json, javaType);
    }

    @SneakyThrows
    @Override
    public Object fromJson(InputStream json, Type type) {
        JavaType javaType = mapper.getTypeFactory().constructType(type);
        return mapper.readValue(json, javaType);
    }
}

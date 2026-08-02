package io.springperf.web.core.codec;

import io.springperf.web.context.WebComponentWrapper;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

public class WrappedHttpBodyConverter extends WebComponentWrapper<GenericHttpMessageConverter<Object>> implements HttpBodyConverter {

    protected final GenericHttpMessageConverter<Object> genericConverter;

    public WrappedHttpBodyConverter(GenericHttpMessageConverter<Object> genericConverter) {
        super(genericConverter);
        this.genericConverter = genericConverter;
    }

    @Override
    public Class<? extends HttpMessageConverter<?>> getConverterClass() {
        return (Class<? extends HttpMessageConverter<?>>) genericConverter.getClass();
    }

    @Override
    public boolean canRead(Type type, Class<?> contextClass, MediaType mediaType) {
        return genericConverter.canRead(type, contextClass, mediaType);
    }

    @Override
    public Object read(Type type, Class<?> contextClass, HttpInputMessage inputMessage) throws IOException, HttpMessageNotReadableException {
        return genericConverter.read(type, contextClass, inputMessage);
    }

    @Override
    public boolean canWrite(Type type, Class<?> clazz, MediaType mediaType) {
        return genericConverter.canWrite(type, clazz, mediaType);
    }

    @Override
    public void write(Object t, Type type, MediaType contentType, HttpOutputMessage outputMessage) throws IOException, HttpMessageNotWritableException {
        genericConverter.write(t, type, contentType, outputMessage);
    }

    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
        return genericConverter.canRead(clazz, mediaType);
    }

    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return genericConverter.canWrite(clazz, mediaType);
    }

    @Override
    public List<MediaType> getSupportedMediaTypes() {
        return genericConverter.getSupportedMediaTypes();
    }

    @Override
    public List<MediaType> getSupportedMediaTypes(Class<?> clazz) {
        return genericConverter.getSupportedMediaTypes(clazz);
    }

    @Override
    public Object read(Class<?> clazz, HttpInputMessage inputMessage) throws IOException, HttpMessageNotReadableException {
        return genericConverter.read(clazz, inputMessage);
    }

    @Override
    public void write(Object t, MediaType contentType, HttpOutputMessage outputMessage) throws IOException, HttpMessageNotWritableException {
        genericConverter.write(t, contentType, outputMessage);
    }
}
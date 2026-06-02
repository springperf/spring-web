package io.springperf.web.core.codec;

import io.springperf.web.context.WebComponentWrapper;
import org.springframework.core.Ordered;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

public class AdaptedHttpBodyConverter<T> extends WebComponentWrapper<HttpMessageConverter<T>> implements HttpBodyConverter<T> {

    private final HttpMessageConverter<T> genericConverter;

    public AdaptedHttpBodyConverter(HttpMessageConverter<T> genericConverter) {
        super(genericConverter);
        this.genericConverter = genericConverter;
    }

    protected int defaultOrderPriority() {
        return Ordered.LOWEST_PRECEDENCE - 15000;
    }

    /**
     * GenericHttpMessageConverter  API
     */
    @Override
    public boolean canRead(Type type, Class<?> contextClass, MediaType mediaType) {
        ResolvableType resolvableType = ResolvableType.forType(type);
        Class<?> resolved = resolvableType.resolve();
        if (resolved == null) {
            return false;
        }
        return genericConverter.canRead(resolved, mediaType);
    }

    @Override
    public T read(Type type, Class<?> contextClass, HttpInputMessage inputMessage) throws IOException, HttpMessageNotReadableException {
        ResolvableType resolvableType = ResolvableType.forType(type);
        Class<?> resolved = resolvableType.resolve();
        if (resolved == null) {
            return null;
        }
        return genericConverter.read((Class<? extends T>) resolved, inputMessage);
    }

    @Override
    public boolean canWrite(Type type, Class<?> valueType, MediaType mediaType) {
        return genericConverter.canWrite(valueType, mediaType);
    }

    @Override
    public void write(T t, Type type, MediaType contentType, HttpOutputMessage outputMessage) throws IOException, HttpMessageNotWritableException {
        genericConverter.write(t, contentType, outputMessage);
    }

    /**
     * HttpMessageConverter  API
     */
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
    public T read(Class<? extends T> clazz, HttpInputMessage inputMessage) throws IOException, HttpMessageNotReadableException {
        return genericConverter.read(clazz, inputMessage);
    }

    @Override
    public void write(T t, MediaType contentType, HttpOutputMessage outputMessage) throws IOException, HttpMessageNotWritableException {
        genericConverter.write(t, contentType, outputMessage);
    }

    @Override
    public Class<? extends HttpMessageConverter> getConverterClass() {
        return genericConverter.getClass();
    }
}

package io.springperf.web.core.codec;

import io.springperf.web.context.WebComponent;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.http.HttpInputMessage;
import org.springframework.lang.Nullable;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

import java.io.IOException;
import java.lang.reflect.Type;

/**
 * Abstraction for HTTP message body conversion.
 * <p>
 * Extends Spring's {@link GenericHttpMessageConverter} to integrate custom
 * serialization/deserialization logic (e.g. JSON, XML) into the web framework.
 */
public interface HttpBodyConverter extends GenericHttpMessageConverter<Object>, WebComponent {

    /**
     * Returns the concrete {@link HttpMessageConverter} class that this converter wraps.
     *
     * @return the converter class
     */
    Class<? extends HttpMessageConverter<?>> getConverterClass();

    /**
     * Whether the converter can read into the given type with the full request context.
     * <p>
     * Default implementation delegates to {@link #canRead(Type, Class, MediaType)}.
     * Subclasses may override to leverage request/mappingContext for optimized decisions.
     */
    default boolean canRead(Type type, Class<?> contextClass, MediaType mediaType,
                             WebServerHttpRequest request, PathMappingContext mappingContext) {
        return canRead(type, contextClass, mediaType);
    }

    /**
     * Whether the converter can write the given type with the full request/response context.
     * <p>
     * Default implementation delegates to {@link #canWrite(Type, Class, MediaType)}.
     * Subclasses may override to leverage request/response/mappingContext for optimized decisions.
     */
    default boolean canWrite(Type type, Class<?> valueType, MediaType mediaType,
                              WebServerHttpRequest request, @Nullable WebServerHttpResponse response,
                              PathMappingContext mappingContext) {
        return canWrite(type, valueType, mediaType);
    }

    /**
     * Read the body with the full request context.
     * <p>
     * Default implementation delegates to {@link #read(Type, Class, HttpInputMessage)}.
     * Subclasses may override to bypass {@link HttpInputMessage} and access the underlying
     * transport buffer directly (e.g. Netty {@code ByteBuf}).
     */
    default Object read(Type type, Class<?> contextClass, HttpInputMessage inputMessage,
                   WebServerHttpRequest request, PathMappingContext mappingContext)
            throws IOException, HttpMessageNotReadableException {
        return read(type, contextClass, inputMessage);
    }

    /**
     * Write the body with the full request/response context.
     * <p>
     * Default implementation delegates to {@link #write(Object, Type, MediaType, HttpOutputMessage)}.
     * Subclasses may override to bypass {@link HttpOutputMessage} and write directly to the
     * underlying transport (e.g. Netty {@code ByteBuf}).
     */
    default void write(Object t, Type type, MediaType contentType, HttpOutputMessage outputMessage,
                       WebServerHttpRequest request, WebServerHttpResponse response,
                       PathMappingContext mappingContext)
            throws IOException, HttpMessageNotWritableException {
        write(t, type, contentType, outputMessage);
    }
}

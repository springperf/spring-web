package io.springperf.web.core.codec.interceptor;

import io.springperf.web.context.WebComponent;
import io.springperf.web.core.codec.HttpBodyConverter;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.lang.reflect.Type;

/**
 * Interceptor for HTTP body encoding and decoding operations.
 * <p>
 * Allows custom processing of request bodies before deserialization and
 * response bodies after serialization, as well as handling of empty bodies.
 */
public interface HttpBodyCodecInterceptor extends WebComponent {

    /**
     * Returns whether this interceptor supports reading (deserializing) the body
     * for the given method parameter and target type.
     */
    boolean supportBodyRead(MethodParameter methodParameter, Type targetType,
                            HttpBodyConverter converter);

    /**
     * Invoked before the request body is deserialized. Allows modification of the input message.
     */
    HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter, Type targetType, HttpBodyConverter converter) throws IOException;

    /**
     * Invoked after the request body has been successfully deserialized.
     *
     * @return the processed body object, possibly modified or replaced
     */
    Object afterBodyRead(Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType, HttpBodyConverter converter);

    /**
     * Invoked when the request body is empty.
     *
     * @param body the original (null or empty) body
     * @return a replacement body object, or {@code null}
     */
    Object handleEmptyBodyRead(@Nullable Object body, HttpInputMessage inputMessage, MethodParameter parameter, Type targetType, HttpBodyConverter converter);

    /**
     * Returns whether this interceptor supports writing (serializing) the body
     * for the given return type.
     */
    boolean supportBodyWrite(MethodParameter returnType, HttpBodyConverter converter);

    /**
     * Invoked before the response body is serialized. Allows modification of the body value.
     *
     * @return the body to serialize, possibly modified or replaced
     */
    Object beforeBodyWrite(@Nullable Object body, MethodParameter returnType, MediaType selectedContentType, HttpBodyConverter converter, ServerHttpRequest request, ServerHttpResponse response);
}

package io.springperf.web.core.codec;

import io.springperf.web.context.WebComponent;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;

/**
 * Abstraction for HTTP message body conversion.
 * <p>
 * Extends Spring's {@link GenericHttpMessageConverter} to integrate custom
 * serialization/deserialization logic (e.g. JSON, XML) into the web framework.
 */
public interface HttpBodyConverter<T> extends GenericHttpMessageConverter<T>, WebComponent {

    /**
     * Returns the concrete {@link HttpMessageConverter} class that this converter wraps.
     *
     * @return the converter class
     */
    Class<? extends HttpMessageConverter> getConverterClass();
}

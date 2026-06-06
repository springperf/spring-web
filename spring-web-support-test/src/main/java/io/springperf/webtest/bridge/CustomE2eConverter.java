package io.springperf.webtest.bridge;

import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * A custom {@link HttpMessageConverter} registered via
 * {@link org.springframework.web.servlet.config.annotation.WebMvcConfigurer#extendMessageConverters}.
 * <p>
 * Handles only {@code application/x-custom} media type with String body.
 * On write, prefixes the body with {@code custom-converter:}.
 */
public class CustomE2eConverter implements HttpMessageConverter<String> {

    private static final MediaType CUSTOM_MEDIA_TYPE = new MediaType("application", "x-custom");
    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
        return String.class.isAssignableFrom(clazz) && CUSTOM_MEDIA_TYPE.isCompatibleWith(mediaType);
    }

    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return String.class.isAssignableFrom(clazz) && CUSTOM_MEDIA_TYPE.isCompatibleWith(mediaType);
    }

    @Override
    public List<MediaType> getSupportedMediaTypes() {
        return Collections.singletonList(CUSTOM_MEDIA_TYPE);
    }

    @Override
    public String read(Class<? extends String> clazz, HttpInputMessage inputMessage)
            throws IOException, HttpMessageNotReadableException {
        return StreamUtils.copyToString(inputMessage.getBody(), UTF_8);
    }

    @Override
    public void write(String s, MediaType contentType, HttpOutputMessage outputMessage)
            throws IOException, HttpMessageNotWritableException {
        String body = "custom-converter:" + s;
        outputMessage.getBody().write(body.getBytes(UTF_8));
    }
}

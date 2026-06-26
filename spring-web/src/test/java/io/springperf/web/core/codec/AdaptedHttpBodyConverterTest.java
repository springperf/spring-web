package io.springperf.web.core.codec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.Ordered;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdaptedHttpBodyConverterTest {

    @Mock
    HttpMessageConverter<String> delegate;

    @Mock
    HttpInputMessage inputMessage;

    @Mock
    HttpOutputMessage outputMessage;

    @Test
    void constructor_storesDelegate() {
        AdaptedHttpBodyConverter<String> converter = new AdaptedHttpBodyConverter<>(delegate);

        assertSame(delegate, converter.getComponent());
    }

    @Test
    void canRead_withType_delegates() {
        when(delegate.canRead(String.class, MediaType.APPLICATION_JSON)).thenReturn(true);

        AdaptedHttpBodyConverter<String> converter = new AdaptedHttpBodyConverter<>(delegate);

        assertTrue(converter.canRead((Type) String.class, null, MediaType.APPLICATION_JSON));
        verify(delegate).canRead(String.class, MediaType.APPLICATION_JSON);
    }

    @Test
    void canRead_withType_delegatesFalse() {
        when(delegate.canRead(String.class, MediaType.APPLICATION_JSON)).thenReturn(false);

        AdaptedHttpBodyConverter<String> converter = new AdaptedHttpBodyConverter<>(delegate);

        assertFalse(converter.canRead((Type) String.class, null, MediaType.APPLICATION_JSON));
    }

    @Test
    void read_withType_delegates() throws IOException {
        when(delegate.read(String.class, inputMessage)).thenReturn("test body");

        AdaptedHttpBodyConverter<String> converter = new AdaptedHttpBodyConverter<>(delegate);
        String result = converter.read((Type) String.class, null, inputMessage);

        assertEquals("test body", result);
        verify(delegate).read(String.class, inputMessage);
    }

    @Test
    void canRead_withClass_delegates() {
        when(delegate.canRead(String.class, MediaType.APPLICATION_JSON)).thenReturn(true);

        AdaptedHttpBodyConverter<String> converter = new AdaptedHttpBodyConverter<>(delegate);

        assertTrue(converter.canRead(String.class, MediaType.APPLICATION_JSON));
        verify(delegate).canRead(String.class, MediaType.APPLICATION_JSON);
    }

    @Test
    void read_withClass_delegates() throws IOException {
        when(delegate.read(String.class, inputMessage)).thenReturn("test");

        AdaptedHttpBodyConverter<String> converter = new AdaptedHttpBodyConverter<>(delegate);

        assertEquals("test", converter.read(String.class, inputMessage));
        verify(delegate).read(String.class, inputMessage);
    }

    @Test
    void canWrite_withClass_delegates() {
        when(delegate.canWrite(String.class, MediaType.APPLICATION_JSON)).thenReturn(true);

        AdaptedHttpBodyConverter<String> converter = new AdaptedHttpBodyConverter<>(delegate);

        assertTrue(converter.canWrite(String.class, MediaType.APPLICATION_JSON));
        verify(delegate).canWrite(String.class, MediaType.APPLICATION_JSON);
    }

    @Test
    void getSupportedMediaTypes_delegates() {
        List<MediaType> expected = Collections.singletonList(MediaType.APPLICATION_JSON);
        when(delegate.getSupportedMediaTypes()).thenReturn(expected);

        AdaptedHttpBodyConverter<String> converter = new AdaptedHttpBodyConverter<>(delegate);

        assertSame(expected, converter.getSupportedMediaTypes());
    }

    @Test
    void getSupportedMediaTypes_withClass_delegates() {
        List<MediaType> expected = Collections.singletonList(MediaType.APPLICATION_JSON);
        when(delegate.getSupportedMediaTypes(String.class)).thenReturn(expected);

        AdaptedHttpBodyConverter<String> converter = new AdaptedHttpBodyConverter<>(delegate);

        assertSame(expected, converter.getSupportedMediaTypes(String.class));
    }

    @Test
    void write_withType_delegates() throws IOException {
        AdaptedHttpBodyConverter<String> converter = new AdaptedHttpBodyConverter<>(delegate);
        converter.write("body", (Type) String.class, MediaType.APPLICATION_JSON, outputMessage);

        verify(delegate).write("body", MediaType.APPLICATION_JSON, outputMessage);
    }

    @Test
    void write_withClass_delegates() throws IOException {
        AdaptedHttpBodyConverter<String> converter = new AdaptedHttpBodyConverter<>(delegate);
        converter.write("body", MediaType.APPLICATION_JSON, outputMessage);

        verify(delegate).write("body", MediaType.APPLICATION_JSON, outputMessage);
    }

    @Test
    void canWrite_withType_delegates() {
        when(delegate.canWrite(String.class, MediaType.APPLICATION_JSON)).thenReturn(true);

        AdaptedHttpBodyConverter<String> converter = new AdaptedHttpBodyConverter<>(delegate);

        assertTrue(converter.canWrite((Type) String.class, String.class, MediaType.APPLICATION_JSON));
        verify(delegate).canWrite(String.class, MediaType.APPLICATION_JSON);
    }

    @Test
    void getConverterClass_returnsDelegateClass() {
        AdaptedHttpBodyConverter<String> converter = new AdaptedHttpBodyConverter<>(delegate);

        assertEquals(delegate.getClass(), converter.getConverterClass());
    }

    @Test
    void defaultOrder_isLowestPrecedenceMinus15000() {
        AdaptedHttpBodyConverter<String> converter = new AdaptedHttpBodyConverter<>(delegate);

        assertEquals(Ordered.LOWEST_PRECEDENCE - 15000, converter.getOrder());
    }
}

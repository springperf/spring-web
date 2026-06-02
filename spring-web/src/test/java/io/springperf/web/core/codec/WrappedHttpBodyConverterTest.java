package io.springperf.web.core.codec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.GenericHttpMessageConverter;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WrappedHttpBodyConverterTest {

    @Mock
    GenericHttpMessageConverter<String> delegate;

    @Mock
    HttpInputMessage inputMessage;

    @Mock
    HttpOutputMessage outputMessage;

    @Test
    void constructor_storesDelegate() {
        WrappedHttpBodyConverter<String> converter = new WrappedHttpBodyConverter<>(delegate);

        assertSame(delegate, converter.getComponent());
    }

    @Test
    void canRead_withType_delegates() {
        when(delegate.canRead((Type) String.class, null, MediaType.APPLICATION_JSON)).thenReturn(true);

        WrappedHttpBodyConverter<String> converter = new WrappedHttpBodyConverter<>(delegate);

        assertTrue(converter.canRead((Type) String.class, null, MediaType.APPLICATION_JSON));
        verify(delegate).canRead((Type) String.class, null, MediaType.APPLICATION_JSON);
    }

    @Test
    void read_withType_delegates() throws IOException {
        when(delegate.read((Type) String.class, null, inputMessage)).thenReturn("test body");

        WrappedHttpBodyConverter<String> converter = new WrappedHttpBodyConverter<>(delegate);
        String result = converter.read((Type) String.class, null, inputMessage);

        assertEquals("test body", result);
        verify(delegate).read((Type) String.class, null, inputMessage);
    }

    @Test
    void canWrite_withType_delegates() {
        when(delegate.canWrite((Type) String.class, String.class, MediaType.APPLICATION_JSON)).thenReturn(true);

        WrappedHttpBodyConverter<String> converter = new WrappedHttpBodyConverter<>(delegate);

        assertTrue(converter.canWrite((Type) String.class, String.class, MediaType.APPLICATION_JSON));
        verify(delegate).canWrite((Type) String.class, String.class, MediaType.APPLICATION_JSON);
    }

    @Test
    void write_withType_delegates() throws IOException {
        WrappedHttpBodyConverter<String> converter = new WrappedHttpBodyConverter<>(delegate);
        converter.write("body", (Type) String.class, MediaType.APPLICATION_JSON, outputMessage);

        verify(delegate).write("body", (Type) String.class, MediaType.APPLICATION_JSON, outputMessage);
    }

    @Test
    void canRead_withClass_delegates() {
        when(delegate.canRead(String.class, MediaType.APPLICATION_JSON)).thenReturn(true);

        WrappedHttpBodyConverter<String> converter = new WrappedHttpBodyConverter<>(delegate);

        assertTrue(converter.canRead(String.class, MediaType.APPLICATION_JSON));
        verify(delegate).canRead(String.class, MediaType.APPLICATION_JSON);
    }

    @Test
    void canWrite_withClass_delegates() {
        when(delegate.canWrite(String.class, MediaType.APPLICATION_JSON)).thenReturn(true);

        WrappedHttpBodyConverter<String> converter = new WrappedHttpBodyConverter<>(delegate);

        assertTrue(converter.canWrite(String.class, MediaType.APPLICATION_JSON));
        verify(delegate).canWrite(String.class, MediaType.APPLICATION_JSON);
    }

    @Test
    void read_withClass_delegates() throws IOException {
        when(delegate.read(String.class, inputMessage)).thenReturn("test");

        WrappedHttpBodyConverter<String> converter = new WrappedHttpBodyConverter<>(delegate);

        assertEquals("test", converter.read(String.class, inputMessage));
        verify(delegate).read(String.class, inputMessage);
    }

    @Test
    void write_withClass_delegates() throws IOException {
        WrappedHttpBodyConverter<String> converter = new WrappedHttpBodyConverter<>(delegate);
        converter.write("body", MediaType.APPLICATION_JSON, outputMessage);

        verify(delegate).write("body", MediaType.APPLICATION_JSON, outputMessage);
    }

    @Test
    void getSupportedMediaTypes_delegates() {
        List<MediaType> expected = Collections.singletonList(MediaType.APPLICATION_JSON);
        when(delegate.getSupportedMediaTypes()).thenReturn(expected);

        WrappedHttpBodyConverter<String> converter = new WrappedHttpBodyConverter<>(delegate);

        assertSame(expected, converter.getSupportedMediaTypes());
    }

    @Test
    void getSupportedMediaTypes_withClass_delegates() {
        List<MediaType> expected = Collections.singletonList(MediaType.APPLICATION_JSON);
        when(delegate.getSupportedMediaTypes(String.class)).thenReturn(expected);

        WrappedHttpBodyConverter<String> converter = new WrappedHttpBodyConverter<>(delegate);

        assertSame(expected, converter.getSupportedMediaTypes(String.class));
    }

    @Test
    void getConverterClass_returnsDelegateClass() {
        WrappedHttpBodyConverter<String> converter = new WrappedHttpBodyConverter<>(delegate);

        assertEquals(delegate.getClass(), converter.getConverterClass());
    }
}
package io.springperf.web.support.async.stream;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.async.stream.StreamEmitter;
import io.springperf.web.core.codec.HttpBodyCodecRegistry;
import io.springperf.web.core.codec.HttpBodyConverter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.ByteArrayOutputStream;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResponseBodyEmitterReturnValueResolverTest {

    @Mock
    WebContext webContext;

    @Mock
    HttpBodyCodecRegistry codecRegistry;

    @Mock
    WebServerHttpRequest req;

    @Mock
    WebServerHttpResponse resp;

    @Mock
    org.springframework.http.HttpHeaders respHeaders;

    private ResponseBodyEmitterReturnValueResolver resolver;

    @BeforeEach
    void setUp() {
        lenient().when(webContext.getWebComponentWithDefault(eq(HttpBodyCodecRegistry.class), any())).thenReturn(codecRegistry);
        resolver = new ResponseBodyEmitterReturnValueResolver();
        resolver.initWithWebContext(webContext);
    }

    @Test
    void preInitializeEmitter_withResponseBodyEmitter_setsEncodeFunction() throws Exception {
        ResponseBodyEmitter emitter = spy(new ResponseBodyEmitter());
        when(resp.getHeaders()).thenReturn(respHeaders);

        resolver.preInitializeEmitter(emitter, req, resp);

        verify(resp).getHeaders();
        // verify no exception
    }

    @Test
    void preInitializeEmitter_withNonResponseBodyEmitter_doesNothing() throws Exception {
        StreamEmitter emitter = mock(StreamEmitter.class);

        resolver.preInitializeEmitter(emitter, req, resp);

        verifyNoInteractions(req);
    }

    @Test
    void initWithWebContext_initializesCodecRegistry() {
        verify(webContext).getWebComponentWithDefault(eq(HttpBodyCodecRegistry.class), any());
    }

    @Test
    void encodeToBytes_withMatchingConverter_returnsBytes() throws Exception {
        HttpBodyConverter converter = mock(HttpBodyConverter.class);
        when(converter.canWrite(null, String.class, null)).thenReturn(true);
        doAnswer(invocation -> {
            HttpOutputMessageCaptor outputMessage = new HttpOutputMessageCaptor();
            // write the data to the output message
            ByteArrayOutputStream baos = (ByteArrayOutputStream) invocation.getArgument(3, org.springframework.http.HttpOutputMessage.class).getBody();
            baos.write("converted".getBytes());
            return null;
        }).when(converter).write(eq("data"), isNull(), isNull(), any());
        when(codecRegistry.getConverters()).thenReturn(Collections.singletonList(converter));

        byte[] result = resolver.encodeToBytes(new HttpHeaders(), "data");

        assertNotNull(result);
        assertTrue(result.length > 0);
    }

    @Test
    void encodeToBytes_withDataWithMediaType_unwrapsAndEncodes() throws Exception {
        HttpBodyConverter converter = mock(HttpBodyConverter.class);
        when(converter.canWrite(null, String.class, MediaType.TEXT_PLAIN)).thenReturn(true);
        doAnswer(invocation -> {
            ByteArrayOutputStream baos = (ByteArrayOutputStream) invocation.getArgument(3, org.springframework.http.HttpOutputMessage.class).getBody();
            baos.write("encoded".getBytes());
            return null;
        }).when(converter).write(any(), isNull(), eq(MediaType.TEXT_PLAIN), any());
        when(codecRegistry.getConverters()).thenReturn(Collections.singletonList(converter));

        ResponseBodyEmitter.DataWithMediaType dataWithType =
                new ResponseBodyEmitter.DataWithMediaType("data", MediaType.TEXT_PLAIN);
        byte[] result = resolver.encodeToBytes(new HttpHeaders(), dataWithType);

        assertNotNull(result);
    }

    @Test
    void encodeToBytes_noMatchingConverter_throwsIllegalArgumentException() {
        when(codecRegistry.getConverters()).thenReturn(Collections.emptyList());

        assertThrows(IllegalArgumentException.class,
                () -> resolver.encodeToBytes(new HttpHeaders(), new Object()));
    }

    @Test
    void getComponentName_returnsStreamEmitterReturnValueResolver() {
        assertEquals("StreamEmitterReturnValueResolver", resolver.getComponentName());
    }

    // Helper to capture output message
    private static class HttpOutputMessageCaptor {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
    }
}
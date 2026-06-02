package io.springperf.web.core.arg.resolver;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.databinder.WebDataBinderRegistry;
import io.springperf.web.core.codec.HttpBodyCodecRegistry;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.http.support.HttpInputMessagePart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartException;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RequestPartResolverTest {

    @Mock
    WebContext webContext;

    @Mock
    HttpBodyCodecRegistry httpBodyCodecRegistry;

    @Mock
    MappingHandlerMethod mappingContext;

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    @Mock
    HttpInputMessagePart part;

    @BeforeEach
    void setUp() {
        lenient().when(webContext.getWebComponent(HttpBodyCodecRegistry.class)).thenReturn(httpBodyCodecRegistry);
        lenient().when(webContext.getWebComponent(WebDataBinderRegistry.class)).thenReturn(null);
    }

    @Test
    void resolveByName_withPart_returnsResolvedBody() throws Exception {
        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        mp.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());

        MultiValueMap<String, HttpInputMessagePart> partMap = new LinkedMultiValueMap<>();
        partMap.add("stringParam", part);

        lenient().when(httpBodyCodecRegistry.readBody(any(), any(), any(), any())).thenReturn("resolved-value");
        when(request.getPartMap()).thenReturn(partMap);

        RequestPartResolver resolver = new RequestPartResolver(webContext, mappingContext, mp);
        Object result = resolver.resolveArgument(request, response);

        assertEquals("resolved-value", result);
    }

    @Test
    void resolveByName_notMultipartRequest_throwsMultipartException() throws Exception {
        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        mp.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());

        when(request.getPartMap()).thenReturn(null);

        RequestPartResolver resolver = new RequestPartResolver(webContext, mappingContext, mp);
        assertThrows(MultipartException.class,
                () -> resolver.resolveArgument(request, response));
    }

    @Test
    void resolveByName_missingPart_returnsNull() throws Exception {
        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        mp.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());

        MultiValueMap<String, HttpInputMessagePart> partMap = new LinkedMultiValueMap<>();
        when(request.getPartMap()).thenReturn(partMap);

        RequestPartResolver resolver = new RequestPartResolver(webContext, mappingContext, mp);
        Object result = resolver.resolveArgument(request, response);

        assertNull(result);
    }

    @SuppressWarnings("unused")
    public void stringParam(String part) {}
}
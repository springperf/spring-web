package io.springperf.web.support.arg.provider;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;

@ExtendWith(MockitoExtension.class)
class HttpServletResponseProviderTest {

    @Mock
    MethodParameter methodParameter;

    @Mock
    MappingHandlerMethod mappingContext;

    private final HttpServletResponseProvider provider = new HttpServletResponseProvider();

    @Test
    void supports_httpServletResponse_returnsTrue() {
        doReturn((Class) HttpServletResponse.class).when(methodParameter).getParameterType();

        assertTrue(provider.supports(methodParameter, mappingContext));
    }

    @Test
    void supports_httpServletRequest_returnsFalse() {
        doReturn((Class) HttpServletRequest.class).when(methodParameter).getParameterType();

        assertFalse(provider.supports(methodParameter, mappingContext));
    }

    @Test
    void supports_stringType_returnsFalse() {
        doReturn((Class) String.class).when(methodParameter).getParameterType();

        assertFalse(provider.supports(methodParameter, mappingContext));
    }

    @Test
    void getResolver_returnsNonNullResolver() {
        assertNotNull(provider.getResolver(methodParameter, mappingContext, null));
    }
}
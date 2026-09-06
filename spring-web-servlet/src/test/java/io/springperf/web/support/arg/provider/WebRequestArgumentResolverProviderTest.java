package io.springperf.web.support.arg.provider;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.WebRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;

@ExtendWith(MockitoExtension.class)
class WebRequestArgumentResolverProviderTest {

    @Mock
    MethodParameter methodParameter;

    @Mock
    MappingHandlerMethod mappingContext;

    private final WebRequestArgumentResolverProvider provider = new WebRequestArgumentResolverProvider();

    @Test
    void supports_webRequest_returnsTrue() {
        doReturn((Class) WebRequest.class).when(methodParameter).getParameterType();

        assertTrue(provider.supports(methodParameter, mappingContext));
    }

    @Test
    void supports_subtypeOfWebRequest_returnsTrue() {
        doReturn((Class) TestWebRequest.class).when(methodParameter).getParameterType();

        assertTrue(provider.supports(methodParameter, mappingContext));
    }

    @Test
    void supports_stringType_returnsFalse() {
        doReturn((Class) String.class).when(methodParameter).getParameterType();

        assertFalse(provider.supports(methodParameter, mappingContext));
    }

    @Test
    void supports_nullType_returnsFalse() {
        doReturn(null).when(methodParameter).getParameterType();

        assertFalse(provider.supports(methodParameter, mappingContext));
    }

    @Test
    void getResolver_returnsNonNullResolver() {
        assertNotNull(provider.getResolver(methodParameter, mappingContext, null));
    }

    @SuppressWarnings("unused")
    abstract static class TestWebRequest implements WebRequest {
    }
}
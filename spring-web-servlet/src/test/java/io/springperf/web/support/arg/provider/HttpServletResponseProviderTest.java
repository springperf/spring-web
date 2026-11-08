package io.springperf.web.support.arg.provider;

import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void supports_returnsFalseForNullType() {
        doReturn(null).when(methodParameter).getParameterType();

        assertFalse(provider.supports(methodParameter, mappingContext));
    }

    @Test
    void getResolver_reusesResolverInstance() {
        Object first = provider.getResolver(methodParameter, mappingContext, null);

        assertNotNull(first);
        assertSame(first, provider.getResolver(methodParameter, mappingContext, null), "resolver 应复用同一实例，避免重复创建");
    }

    @Test
    void getResolver_resolvesCurrentHttpServletResponse() throws Exception {
        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        RequestContext requestContext = mock(RequestContext.class);
        ServletAdapterContext adapterContext = mock(ServletAdapterContext.class);
        HttpServletResponse servletResponse = mock(HttpServletResponse.class);
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(ServletAttribute.getAttributeKey())).thenReturn(adapterContext);
        when(adapterContext.getResponse()).thenReturn(servletResponse);

        StaticArgumentResolver resolver = provider.getResolver(methodParameter, mappingContext, null);

        assertSame(servletResponse, resolver.resolveArgument(request, response),
                "resolver 必须注入当前请求的 HttpServletResponse，不得与 request 取反");
    }
}

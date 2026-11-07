package io.springperf.web.support.arg.provider;

import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void getResolver_reusesResolverInstance() {
        Object first = provider.getResolver(methodParameter, mappingContext, null);

        assertNotNull(first);
        assertSame(first, provider.getResolver(methodParameter, mappingContext, null),
                "resolver 应复用同一实例，避免重复创建");
    }

    @Test
    void getResolver_wrapsCurrentRequestAndResponse() throws Exception {
        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        RequestContext requestContext = mock(RequestContext.class);
        ServletAdapterContext adapterContext = mock(ServletAdapterContext.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        HttpServletResponse servletResponse = mock(HttpServletResponse.class);
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(ServletAttribute.getAttributeKey())).thenReturn(adapterContext);
        when(adapterContext.getRequest()).thenReturn(servletRequest);
        when(adapterContext.getResponse()).thenReturn(servletResponse);

        StaticArgumentResolver resolver = provider.getResolver(methodParameter, mappingContext, null);

        ServletWebRequest webRequest = assertInstanceOf(ServletWebRequest.class,
                resolver.resolveArgument(request, response));
        assertSame(servletRequest, webRequest.getRequest(), "WebRequest 必须包装当前 servlet request");
        assertSame(servletResponse, webRequest.getResponse(), "不得把 request 同时当作 response 传入");
    }

    @SuppressWarnings("unused")
    abstract static class TestWebRequest implements WebRequest {
    }
}
package io.springperf.web.support.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.model.SessionAttributesInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.SessionStatus;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SessionStatusArgumentResolverProviderTest {

    private final SessionStatusArgumentResolverProvider provider = new SessionStatusArgumentResolverProvider();

    static class Controller {
        public void handler(SessionStatus status) {
        }
    }

    private MethodParameter sessionStatusParam() throws Exception {
        return new MethodParameter(Controller.class.getMethod("handler", SessionStatus.class), 0);
    }

    private WebServerHttpRequest mockRequest(RequestContext ctx) {
        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        when(request.getRequestContext()).thenReturn(ctx);
        return request;
    }

    private RequestContext mockContext() {
        RequestContext ctx = mock(RequestContext.class);
        Map<RequestAttribute<?>, Object> attrs = new HashMap<>();
        when(ctx.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> attrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ctx).setAttribute(any(RequestAttribute.class), any());
        return ctx;
    }

    @Test
    void supports_sessionStatus_true() throws Exception {
        assertTrue(provider.supports(sessionStatusParam(), mock(MappingHandlerMethod.class)));
    }

    @Test
    void supports_otherType_false() {
        MethodParameter p = mock(MethodParameter.class);
        when(p.getParameterType()).thenReturn((Class) String.class);
        assertFalse(provider.supports(p, mock(MappingHandlerMethod.class)));
    }

    @Test
    void getResolver_withoutBean_createsLazyInterceptor() throws Exception {
        WebContext webContext = mock(WebContext.class);
        when(webContext.getBeanFromCtx(SessionAttributesInterceptor.class)).thenReturn(null);
        StaticArgumentResolver resolver = provider.getResolver(sessionStatusParam(), null, webContext);

        RequestContext ctx = mockContext();
        WebServerHttpRequest request = mockRequest(ctx);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        Object result = resolver.resolveArgument(request, response);
        assertTrue(result instanceof SessionStatus, "应返回 SessionStatus 实例");
        assertSame(result, resolver.resolveArgument(request, response), "同一请求应复用同一 SessionStatus");
    }

    @Test
    void getResolver_withBean_usesRegisteredInterceptor() throws Exception {
        WebContext webContext = mock(WebContext.class);
        SessionAttributesInterceptor interceptor = mock(SessionAttributesInterceptor.class);
        when(webContext.getBeanFromCtx(SessionAttributesInterceptor.class)).thenReturn(interceptor);

        RequestContext ctx = mockContext();
        WebServerHttpRequest request = mockRequest(ctx);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        SessionStatus status = mock(SessionStatus.class);
        when(interceptor.getOrCreateSessionStatus(request, response)).thenReturn(status);

        StaticArgumentResolver resolver = provider.getResolver(sessionStatusParam(), null, webContext);
        assertSame(status, resolver.resolveArgument(request, response));
    }
}
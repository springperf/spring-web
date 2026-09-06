package io.springperf.web.support.model;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.model.ModelContext;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SessionAttributesInterceptorTest {

    @SessionAttributes("wizard")
    static class WizardController {
        public void step1() {
        }
    }

    static class PlainController {
        public void hello() {
        }
    }

    private final SessionAttributesInterceptor interceptor = new SessionAttributesInterceptor();

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

    private MappingHandlerMethod handler(Class<?> controller, String method) throws Exception {
        PathMappingContext ctx = mock(PathMappingContext.class);
        when(ctx.getBeanType()).thenReturn((Class) controller);
        return ctx;
    }

    @Test
    void preHandle_nonHandlerObject_returnsTrue() throws Exception {
        WebServerHttpRequest request = mockRequest(mockContext());
        assertTrue(interceptor.preHandle(request, mock(WebServerHttpResponse.class), "not-a-handler"));
    }

    @Test
    void preHandle_handlerWithoutSessionAttributes_returnsTrue() throws Exception {
        WebServerHttpRequest request = mockRequest(mockContext());
        assertTrue(interceptor.preHandle(request, mock(WebServerHttpResponse.class),
                handler(PlainController.class, "hello")));
    }

    @Test
    void getOrCreateSessionStatus_sameRequest_reusesInstance() {
        WebServerHttpRequest request = mockRequest(mockContext());
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        SessionStatus first = interceptor.getOrCreateSessionStatus(request, response);
        SessionStatus second = interceptor.getOrCreateSessionStatus(request, response);
        assertSame(first, second, "同一请求应复用同一 SessionStatus");
        assertFalse(first.isComplete());
    }

    @Test
    void getOrCreateSessionStatus_markComplete_visibleToNextCall() {
        WebServerHttpRequest request = mockRequest(mockContext());
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);

        SessionStatus status = interceptor.getOrCreateSessionStatus(request, response);
        status.setComplete();
        assertTrue(interceptor.getOrCreateSessionStatus(request, response).isComplete(),
                "setComplete 后同一请求内再次读取应为 complete");
    }

    @Test
    void supports_sessionAttributesHandler_resolvesHandler() throws Exception {
        // @ControllerAdvice(annotations=SessionAttributes) 由 InterceptorRegistry 负责类级匹配；
        // 此处验证拦截器自身也能按 beanType 解析 SessionAttributesHandler 不抛错。
        assertNotNull(interceptor.resolveHandlerForTest(WizardController.class));
        assertNull(interceptor.resolveHandlerForTest(PlainController.class));
    }

    private ServletAdapterContext adapterWithMockServlet() {
        ServletAdapterContext adapter = mock(ServletAdapterContext.class);
        when(adapter.getRequest()).thenReturn(new MockHttpServletRequest());
        when(adapter.getResponse()).thenReturn(new MockHttpServletResponse());
        return adapter;
    }

    @Test
    void preHandle_restoresSessionAttributesIntoModel() throws Exception {
        RequestContext ctx = mockContext();
        ServletAdapterContext adapter = adapterWithMockServlet();
        MockHttpServletRequest servletRequest = (MockHttpServletRequest) adapter.getRequest();
        servletRequest.getSession(true).setAttribute("wizard", "magic-value");
        ServletAttribute.setAdapterContext(ctx, adapter);

        WebServerHttpRequest request = mockRequest(ctx);
        assertTrue(interceptor.preHandle(request, mock(WebServerHttpResponse.class),
                handler(WizardController.class, "step1")));

        ModelMap model = ModelContext.get(request);
        assertNotNull(model, "preHandle 恢复 session 属性时应创建 Model");
        assertEquals("magic-value", model.get("wizard"));
    }

    @Test
    void preHandle_noSessionAttributesInStore_noModelMutation() throws Exception {
        RequestContext ctx = mockContext();
        ServletAdapterContext adapter = adapterWithMockServlet();
        ServletAttribute.setAdapterContext(ctx, adapter);

        WebServerHttpRequest request = mockRequest(ctx);
        assertTrue(interceptor.preHandle(request, mock(WebServerHttpResponse.class),
                handler(WizardController.class, "step1")));
        assertNull(ModelContext.get(request), "session 无属性时不应创建 Model");
    }

    @Test
    void postHandle_nonHandlerObject_returnsGracefully() {
        interceptor.postHandle(mockRequest(mockContext()), mock(WebServerHttpResponse.class), "plain", null);
    }

    @Test
    void postHandle_handlerWithoutSessionAttrs_returnsGracefully() throws Exception {
        interceptor.postHandle(mockRequest(mockContext()), mock(WebServerHttpResponse.class),
                handler(PlainController.class, "hello"), null);
    }

    @Test
    void postHandle_sessionComplete_cleansUpSessionAttributes() throws Exception {
        RequestContext ctx = mockContext();
        ServletAdapterContext adapter = adapterWithMockServlet();
        MockHttpServletRequest servletRequest = (MockHttpServletRequest) adapter.getRequest();
        servletRequest.getSession(true).setAttribute("wizard", "magic-value");
        ServletAttribute.setAdapterContext(ctx, adapter);

        WebServerHttpRequest request = mockRequest(ctx);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        SessionStatus status = interceptor.getOrCreateSessionStatus(request, response);
        status.setComplete();

        interceptor.postHandle(request, response, handler(WizardController.class, "step1"), null);

        assertNull(servletRequest.getSession(false).getAttribute("wizard"),
                "SessionStatus complete 时 postHandle 应清理 session 属性");
    }

    @Test
    void postHandle_modelAttributesStoredToSession() throws Exception {
        RequestContext ctx = mockContext();
        ServletAdapterContext adapter = adapterWithMockServlet();
        ServletAttribute.setAdapterContext(ctx, adapter);

        WebServerHttpRequest request = mockRequest(ctx);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        ModelContext.getOrCreate(request).addAttribute("wizard", "persisted");

        interceptor.postHandle(request, response, handler(WizardController.class, "step1"), null);

        MockHttpServletRequest servletRequest = (MockHttpServletRequest) adapter.getRequest();
        assertEquals("persisted", servletRequest.getSession(false).getAttribute("wizard"),
                "postHandle 应将 Model 中声明的 session 属性写回 session");
    }
}

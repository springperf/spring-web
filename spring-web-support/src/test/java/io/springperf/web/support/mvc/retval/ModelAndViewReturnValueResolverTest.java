package io.springperf.web.support.mvc.retval;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.View;
import io.springperf.web.view.ViewResolverRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.ModelAndView;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelAndViewReturnValueResolverTest {

    @Mock WebContext webContext;
    @Mock ViewResolverRegistry viewResolverRegistry;
    @Mock WebServerHttpRequest req;
    @Mock WebServerHttpResponse resp;
    @Mock RequestContext requestContext;
    @Mock MappingHandlerMethod mappingContext;

    private ModelAndViewReturnValueResolver resolver;

    @BeforeEach
    void setUp() {
        lenient().when(webContext.getWebComponentWithDefault(eq(ViewResolverRegistry.class), any()))
                .thenReturn(viewResolverRegistry);
        resolver = new ModelAndViewReturnValueResolver();
        resolver.initWithWebContext(webContext);
    }

    private MethodParameter mavParam() throws Exception {
        return new MethodParameter(getClass().getMethod("dummyMav"), -1);
    }

    @SuppressWarnings("unused")
    public ModelAndView dummyMav() {
        return null;
    }

    @Test
    void supportsReturnType_mav_true() throws Exception {
        assertTrue(resolver.supportsReturnType(mavParam(), mappingContext));
    }

    @Test
    void supportsReturnType_nonMav_false() throws Exception {
        MethodParameter p = mock(MethodParameter.class);
        when(p.getParameterType()).thenReturn((Class) String.class);
        assertFalse(resolver.supportsReturnType(p, mappingContext));
    }

    @Test
    void supportsReturnValue_referenceMav_true() {
        ModelAndView mav = new ModelAndView("home");
        assertTrue(resolver.supportsReturnValue(mav, req, resp));
    }

    @Test
    void supportsReturnValue_nonMav_false() {
        assertFalse(resolver.supportsReturnValue("home", req, resp));
    }

    @Test
    void supportsReturnValue_clearedMav_false() {
        ModelAndView mav = new ModelAndView("home");
        mav.clear();
        assertFalse(resolver.supportsReturnValue(mav, req, resp));
    }

    @Test
    void supportsReturnValue_viewMav_false() {
        ModelAndView mav = new ModelAndView(mock(org.springframework.web.servlet.View.class));
        assertFalse(resolver.supportsReturnValue(mav, req, resp), "非引用(直接 View)类型不支持");
    }

    @Test
    void resolveReturnValue_viewNameNull_returns() throws Exception {
        ModelAndView mav = new ModelAndView();
        resolver.resolveReturnValue(mav, mavParam(), req, resp);
        verify(resp, never()).setHandled();
    }

    @Test
    void resolveReturnValue_viewNotFound_returns() throws Exception {
        ModelAndView mav = new ModelAndView("missing");
        when(viewResolverRegistry.resolve(eq("missing"), eq(req))).thenReturn(null);
        resolver.resolveReturnValue(mav, mavParam(), req, resp);
        verify(resp, never()).setHandled();
    }

    @Test
    void resolveReturnValue_rendersView_withModel() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        when(resp.getHeaders()).thenReturn(headers);
        when(req.getRequestContext()).thenReturn(requestContext);
        Map<RequestAttribute<?>, Object> attrs = new HashMap<>();
        when(requestContext.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> attrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(requestContext).setAttribute(any(RequestAttribute.class), any());

        View view = mock(View.class);
        when(view.getContentType()).thenReturn("text/html;charset=UTF-8");
        when(viewResolverRegistry.resolve(eq("home"), eq(req))).thenReturn(view);

        ModelAndView mav = new ModelAndView("home");
        mav.addObject("name", "Perf");
        resolver.resolveReturnValue(mav, mavParam(), req, resp);

        verify(resp).setHandled();
        verify(view).render(any(), eq(req), eq(resp));
        assertEquals("text/html;charset=UTF-8", headers.getFirst(HttpHeaders.CONTENT_TYPE));
    }

    @Test
    void resolveReturnValue_setsStatusCode_fromMav() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        when(resp.getHeaders()).thenReturn(headers);
        when(req.getRequestContext()).thenReturn(requestContext);
        Map<RequestAttribute<?>, Object> attrs = new HashMap<>();
        when(requestContext.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> attrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(requestContext).setAttribute(any(RequestAttribute.class), any());

        View view = mock(View.class);
        when(view.getContentType()).thenReturn(null);
        when(viewResolverRegistry.resolve(eq("home"), eq(req))).thenReturn(view);
        when(resp.getCharacterEncoding()).thenReturn(null);

        ModelAndView mav = new ModelAndView("home");
        mav.setStatus(HttpStatus.CREATED);
        resolver.resolveReturnValue(mav, mavParam(), req, resp);

        verify(resp).setStatusCode(HttpStatus.CREATED);
        verify(resp).setHandled();
        assertTrue(headers.getFirst(HttpHeaders.CONTENT_TYPE).startsWith("text/html;charset=UTF-8"));
    }

    @Test
    void resolveReturnValue_redirectView_sets302() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        when(resp.getHeaders()).thenReturn(headers);
        when(req.getRequestContext()).thenReturn(requestContext);
        Map<RequestAttribute<?>, Object> attrs = new HashMap<>();
        when(requestContext.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> attrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(requestContext).setAttribute(any(RequestAttribute.class), any());
        when(req.getWebContext()).thenReturn(webContext);
        when(webContext.getContextPath()).thenReturn("/app");

        ModelAndView mav = new ModelAndView("redirect:/home");
        resolver.resolveReturnValue(mav, mavParam(), req, resp);

        verify(resp).setHandled();
        assertTrue(headers.getFirst(HttpHeaders.LOCATION).contains("/app/home"));
    }

    @Test
    void getOrder_low() {
        assertEquals(Integer.MAX_VALUE - 200, resolver.getOrder());
    }
}

package io.springperf.web.support.arg.provider;

import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.PerfHttpServletResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.SessionAttribute;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionAttributeArgumentResolverProviderTest {

    @Mock WebServerHttpRequest request;
    @Mock WebServerHttpResponse response;
    @Mock RequestContext requestContext;
    @Mock MappingHandlerMethod mappingContext;
    @Mock HttpServletRequest servletRequest;
    @Mock HttpSession session;

    private final SessionAttributeArgumentResolverProvider provider = new SessionAttributeArgumentResolverProvider();

    static class Controller {
        public void handler(@SessionAttribute("user") String user,
                            @SessionAttribute(name = "nick", required = false) String nick) {
        }

        public void named(@SessionAttribute String token) {
        }
    }

    private static MethodParameter param(int index) throws Exception {
        MethodParameter p = new MethodParameter(Controller.class.getMethod("handler", String.class, String.class), index);
        p.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
        return p;
    }

    @BeforeEach
    void setUp() {
        Map<RequestAttribute<?>, Object> attrs = new HashMap<>();
        lenient().when(request.getRequestContext()).thenReturn(requestContext);
        lenient().when(requestContext.getAttribute(any(RequestAttribute.class)))
                .thenAnswer(inv -> attrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(requestContext).setAttribute(any(RequestAttribute.class), any());

        PerfHttpServletRequest perfRequest = mock(PerfHttpServletRequest.class);
        PerfHttpServletResponse perfResponse = mock(PerfHttpServletResponse.class);
        ServletAdapterContext adapterContext = new ServletAdapterContext(perfRequest, perfResponse, null);
        adapterContext.setRequest(servletRequest);
        ServletAttribute.setAdapterContext(requestContext, adapterContext);
    }

    @Test
    void supports_withAnnotation_true() throws Exception {
        assertTrue(provider.supports(param(0), mappingContext));
        assertTrue(provider.supports(param(1), mappingContext));
    }

    @Test
    void supports_withoutAnnotation_false() throws Exception {
        MethodParameter p = mock(MethodParameter.class);
        when(p.hasParameterAnnotation(SessionAttribute.class)).thenReturn(false);
        assertFalse(provider.supports(p, mappingContext));
    }

    @Test
    void resolve_requiredPresent_returnsValue() throws Exception {
        when(servletRequest.getSession(false)).thenReturn(session);
        when(session.getAttribute("user")).thenReturn("alice");
        StaticArgumentResolver resolver = provider.getResolver(param(0), mappingContext, null);
        assertEquals("alice", resolver.resolveArgument(request, response));
    }

    @Test
    void resolve_requiredMissing_throws() throws Exception {
        when(servletRequest.getSession(false)).thenReturn(session);
        when(session.getAttribute("user")).thenReturn(null);
        StaticArgumentResolver resolver = provider.getResolver(param(0), mappingContext, null);
        assertThrows(ServletRequestBindingException.class, () -> resolver.resolveArgument(request, response));
    }

    @Test
    void resolve_noSession_requiredMissing_throws() throws Exception {
        when(servletRequest.getSession(false)).thenReturn(null);
        StaticArgumentResolver resolver = provider.getResolver(param(0), mappingContext, null);
        assertThrows(ServletRequestBindingException.class, () -> resolver.resolveArgument(request, response));
    }

    @Test
    void resolve_notRequiredMissing_returnsNull() throws Exception {
        when(servletRequest.getSession(false)).thenReturn(session);
        when(session.getAttribute("nick")).thenReturn(null);
        StaticArgumentResolver resolver = provider.getResolver(param(1), mappingContext, null);
        assertNull(resolver.resolveArgument(request, response));
    }

    @Test
    void resolve_notRequiredNoSession_returnsNull() throws Exception {
        when(servletRequest.getSession(false)).thenReturn(null);
        StaticArgumentResolver resolver = provider.getResolver(param(1), mappingContext, null);
        assertNull(resolver.resolveArgument(request, response));
    }

    @Test
    void resolve_nameFromParameterName_whenAnnotationNameEmpty() throws Exception {
        MethodParameter p = new MethodParameter(Controller.class.getMethod("named", String.class), 0);
        p.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
        when(servletRequest.getSession(false)).thenReturn(session);
        when(session.getAttribute("token")).thenReturn("tok1");
        StaticArgumentResolver resolver = provider.getResolver(p, mappingContext, null);
        assertEquals("tok1", resolver.resolveArgument(request, response));
    }
}

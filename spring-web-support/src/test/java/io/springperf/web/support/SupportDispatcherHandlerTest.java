package io.springperf.web.support;

import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.PerfHttpServletResponse;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupportDispatcherHandlerTest {

    @Mock
    WebServerHttpRequest req;

    @Mock
    WebServerHttpResponse resp;

    @Mock
    RequestContext requestContext;

    @Mock
    ServletAdapterContext adapterContext;

    @Mock
    PerfHttpServletRequest restRequest;

    @Mock
    PerfHttpServletResponse restResponse;

    @AfterEach
    void cleanUp() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void getComponentName_returnsDispatcherHandler() {
        SupportDispatcherHandler handler = new SupportDispatcherHandler();

        assertEquals("DispatcherHandler", handler.getComponentName());
    }

    @Test
    void buildRequestAttributes_withAdapterContext_usesExistingServletWrappers() {
        when(req.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(ServletAdapterContext.REQUEST_ATTRIBUTE_NAME)).thenReturn(adapterContext);
        when(adapterContext.getRequest()).thenReturn(restRequest);
        when(adapterContext.getResponse()).thenReturn(restResponse);

        SupportDispatcherHandler handler = new SupportDispatcherHandler();
        ServletRequestAttributes attrs = handler.buildRequestAttributes(req, resp);

        assertNotNull(attrs);
        assertSame(restRequest, attrs.getRequest());
        assertSame(restResponse, attrs.getResponse());
    }

    @Test
    void buildRequestAttributes_withoutAdapterContext_createsNewServletWrappers() {
        when(req.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(ServletAdapterContext.REQUEST_ATTRIBUTE_NAME)).thenReturn(null);

        SupportDispatcherHandler handler = new SupportDispatcherHandler();
        ServletRequestAttributes attrs = handler.buildRequestAttributes(req, resp);

        assertNotNull(attrs);
        assertNotNull(attrs.getRequest());
        assertNotNull(attrs.getResponse());
    }

    @Test
    void buildRequestAttributes_withNullRequestContext_throwsNpe() {
        when(req.getRequestContext()).thenReturn(null);

        SupportDispatcherHandler handler = new SupportDispatcherHandler();
        assertThrows(NullPointerException.class, () -> handler.buildRequestAttributes(req, resp));
    }

    @Test
    void removeContextHolders_resetsRequestContextHolder() {
        SupportDispatcherHandler handler = new SupportDispatcherHandler();
        handler.removeContextHolders(req, resp);

        assertNull(RequestContextHolder.getRequestAttributes());
    }
}
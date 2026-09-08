package io.springperf.web.support.servlet;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.SupportDispatcherHandler;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerfRequestDispatcherTest {

    @Mock WebServerHttpRequest webRequest;
    @Mock WebServerHttpResponse webResponse;
    @Mock WebContext webContext;
    @Mock SupportDispatcherHandler dispatcherHandler;
    @Mock io.springperf.web.http.RequestContext requestContext;

    private PerfHttpServletRequest servletRequest;
    private PerfHttpServletResponse servletResponse;
    private PerfRequestDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        lenient().when(webRequest.getWebContext()).thenReturn(webContext);
        lenient().when(webRequest.getRequestContext()).thenReturn(requestContext);
        lenient().when(webRequest.getUriStr()).thenReturn("/original");
        lenient().when(webRequest.getPath()).thenReturn("/original");
        lenient().when(webRequest.getUriStrWithQuery()).thenReturn("/original?page=1");
        lenient().when(webContext.getContextPath()).thenReturn("/api");
        lenient().when(webContext.getDispatcherHandler()).thenReturn(dispatcherHandler);
        lenient().when(webResponse.getHeaders()).thenReturn(new org.springframework.http.HttpHeaders());

        servletRequest = new PerfHttpServletRequest(webRequest);
        servletResponse = new PerfHttpServletResponse(webResponse);
        dispatcher = new PerfRequestDispatcher("/target");
    }

    @Test
    void forward_setsDispatcherTypeAndAttributes() throws Exception {
        when(webResponse.isCommitted()).thenReturn(false);

        dispatcher.forward(servletRequest, servletResponse);

        assertEquals(DispatcherType.FORWARD, servletRequest.getDispatcherType());
        verify(requestContext).setAttribute(RequestDispatcher.FORWARD_REQUEST_URI, "/original");
        verify(requestContext).setAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH, "/api");
        verify(webResponse).resetBuffer();
        verify(webResponse).setStatusCode(org.springframework.http.HttpStatus.OK);
        verify(dispatcherHandler).forward(eq(webRequest), eq(webResponse), eq("/target"));
    }

    @Test
    void forward_committed_throws() {
        when(webResponse.isCommitted()).thenReturn(true);
        assertThrows(IllegalStateException.class, () -> dispatcher.forward(servletRequest, servletResponse));
    }

    @Test
    void include_setsDispatcherTypeAndRestores() throws Exception {
        dispatcher.include(servletRequest, servletResponse);

        assertEquals(DispatcherType.REQUEST, servletRequest.getDispatcherType());
        verify(requestContext).setAttribute(RequestDispatcher.INCLUDE_REQUEST_URI, "/original");
        verify(dispatcherHandler).include(eq(webRequest), any(WebServerHttpResponse.class), eq("/target"));
    }

    @Test
    void include_restoresDispatcherTypeOnException() {
        doThrow(new RuntimeException("include failed"))
                .when(dispatcherHandler).include(any(), any(), any());

        assertThrows(RuntimeException.class, () -> dispatcher.include(servletRequest, servletResponse));
        assertEquals(DispatcherType.REQUEST, servletRequest.getDispatcherType());
    }

    @Test
    void getRequestDispatcher_returnsDispatcher() {
        assertNotNull(servletRequest.getRequestDispatcher("/path"));
    }

    @Test
    void getRequestDispatcher_null_returnsNull() {
        assertNull(servletRequest.getRequestDispatcher(null));
    }

    @Test
    void forward_wrappedRequest_unwrapsPerfRequest() throws Exception {
        when(webResponse.isCommitted()).thenReturn(false);
        javax.servlet.http.HttpServletRequestWrapper wrapped =
                new javax.servlet.http.HttpServletRequestWrapper(servletRequest);

        dispatcher.forward(wrapped, servletResponse);

        verify(dispatcherHandler).forward(eq(webRequest), eq(webResponse), eq("/target"));
    }

    @Test
    void forward_wrappedResponse_unwrapsPerfResponse() throws Exception {
        when(webResponse.isCommitted()).thenReturn(false);
        javax.servlet.http.HttpServletResponseWrapper wrapped =
                new javax.servlet.http.HttpServletResponseWrapper(servletResponse);

        dispatcher.forward(servletRequest, wrapped);

        verify(dispatcherHandler).forward(eq(webRequest), eq(webResponse), eq("/target"));
    }

    @Test
    void include_wrappedResponse_unwrapsPerfResponse() throws Exception {
        javax.servlet.http.HttpServletResponseWrapper wrapped =
                new javax.servlet.http.HttpServletResponseWrapper(servletResponse);

        dispatcher.include(servletRequest, wrapped);

        verify(dispatcherHandler).include(eq(webRequest), any(WebServerHttpResponse.class), eq("/target"));
    }

    @Test
    void forward_unresolvableRequest_throwsServletException() {
        javax.servlet.ServletRequest plainRequest = mock(javax.servlet.ServletRequest.class);
        assertThrows(javax.servlet.ServletException.class,
                () -> dispatcher.forward(plainRequest, servletResponse));
    }

    @Test
    void include_unresolvableResponse_throwsServletException() {
        javax.servlet.ServletResponse plainResponse = mock(javax.servlet.ServletResponse.class);
        assertThrows(javax.servlet.ServletException.class,
                () -> dispatcher.include(servletRequest, plainResponse));
    }

    @Test
    void forward_nonSupportDispatcher_throwsServletException() throws Exception {
        when(webResponse.isCommitted()).thenReturn(false);
        when(webContext.getDispatcherHandler()).thenReturn(new io.springperf.web.core.DispatcherHandler());

        assertThrows(javax.servlet.ServletException.class,
                () -> dispatcher.forward(servletRequest, servletResponse));
    }
}

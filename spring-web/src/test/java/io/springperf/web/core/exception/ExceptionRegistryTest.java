package io.springperf.web.core.exception;

import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExceptionRegistryTest {

    ExceptionRegistry registry;

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    @Mock
    RequestContext requestContext;

    @Mock
    HandlerExceptionResolver resolver;

    @BeforeEach
    void setUp() {
        registry = new ExceptionRegistry();
        when(request.getRequestContext()).thenReturn(requestContext);
    }

    @Test
    void handle_resolverTrue_doesNotSendError() {
        registry.resolvers.add(resolver);
        when(resolver.resolveException(request, response, null, null)).thenReturn(true);

        registry.handle(null, request, response);

        verify(response, never()).sendError(any(HttpStatus.class));
        verify(response, never()).sendError(any(HttpStatus.class), anyString());
    }

    @Test
    void handle_noResolver_sendsError500() {
        registry.handle(new RuntimeException("test error"), request, response);

        verify(response).sendError(HttpStatus.INTERNAL_SERVER_ERROR, "RuntimeException: test error");
    }

    @Test
    void handle_resolverException_doesNotThrow() {
        registry.resolvers.add(resolver);
        when(resolver.resolveException(any(), any(), any(), any())).thenThrow(new RuntimeException("resolver error"));

        registry.handle(new RuntimeException("test error"), request, response);

        verify(response, never()).sendError(any(HttpStatus.class));
        verify(response, never()).sendError(any(HttpStatus.class), anyString());
    }

    @Test
    void doHandle_resolverTrue_returnsTrue() {
        registry.resolvers.add(resolver);
        when(resolver.resolveException(request, response, null, null)).thenReturn(true);

        assertTrue(registry.doHandle(null, request, response));
    }

    @Test
    void doHandle_noResolver_returnsFalse() {
        assertFalse(registry.doHandle(null, request, response));
    }

    @Test
    void doHandle_withPathMappingContext_usesHandlerMethod() {
        registry.resolvers.add(resolver);
        when(resolver.resolveException(request, response, null, null)).thenReturn(false);

        assertFalse(registry.doHandle(null, request, response));
    }

    }
package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.endpoint.ApiVersion;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.WebEndpointHttpMethod;
import org.springframework.boot.actuate.endpoint.web.WebOperationRequestPredicate;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActuatorPathMappingContextTest {

    @Mock WebServerHttpRequest request;
    @Mock WebServerHttpResponse response;

    private final EndpointMediaTypes mediaTypes = EndpointMediaTypes.DEFAULT;
    private final WebOperationRequestPredicate predicate = new WebOperationRequestPredicate(
            "/health", WebEndpointHttpMethod.GET,
            Collections.emptyList(), Collections.singletonList("application/json"));

    @Test
    void constructor_linksEndpoint_setsFields() {
        LinksOperationInvoker invoker = new LinksOperationInvoker();
        ActuatorPathMappingContext ctx = new ActuatorPathMappingContext(
                invoker, "/actuator", null, null,
                null, mediaTypes, "/actuator", WebServerNamespace.SERVER);

        assertNull(ctx.getOperation());
        assertNull(ctx.getPredicate());
    }

    @Test
    void constructor_regularEndpoint_setsFields() {
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(null, predicate, Collections.emptyList());
        ActuatorPathMappingContext ctx = new ActuatorPathMappingContext(
                invoker, "/actuator/health", null, predicate,
                null, mediaTypes, "/actuator", WebServerNamespace.SERVER);

        assertNull(ctx.getOperation());
        assertSame(predicate, ctx.getPredicate());
    }

    @Test
    void buildActuatorArguments_withParameters() throws Throwable {
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(null, predicate, Collections.emptyList());
        ActuatorPathMappingContext ctx = new ActuatorPathMappingContext(
                invoker, "/actuator/health", null, predicate,
                null, mediaTypes, "/actuator", WebServerNamespace.SERVER);

        HttpHeaders headers = new HttpHeaders();
        when(request.getHeaders()).thenReturn(headers);

        RequestContext requestContext = mock(RequestContext.class);
        when(request.getRequestContext()).thenReturn(requestContext);

        Map<String, Object> args = invokeBuildActuatorArguments(ctx);
        assertNotNull(args);
        assertTrue(args.containsKey("Accept"));
    }

    @Test
    void resolveApiVersion_v2AcceptHeader() throws Exception {
        LinksOperationInvoker invoker = new LinksOperationInvoker();
        ActuatorPathMappingContext ctx = new ActuatorPathMappingContext(
                invoker, "/actuator", null, null,
                null, mediaTypes, "/actuator", WebServerNamespace.SERVER);

        List<MediaType> accepts = Collections.singletonList(
                MediaType.parseMediaType("application/vnd.spring-boot.actuator.v2+json"));

        ApiVersion version = invokeResolveApiVersion(ctx, accepts);
        assertEquals(ApiVersion.V2, version);
    }

    @Test
    void resolveApiVersion_v3AcceptHeader() throws Exception {
        LinksOperationInvoker invoker = new LinksOperationInvoker();
        ActuatorPathMappingContext ctx = new ActuatorPathMappingContext(
                invoker, "/actuator", null, null,
                null, mediaTypes, "/actuator", WebServerNamespace.SERVER);

        List<MediaType> accepts = Collections.singletonList(
                MediaType.parseMediaType("application/vnd.spring-boot.actuator.v3+json"));

        ApiVersion version = invokeResolveApiVersion(ctx, accepts);
        assertEquals(ApiVersion.V3, version);
    }

    @Test
    void resolveApiVersion_nullAcceptHeaders() throws Exception {
        LinksOperationInvoker invoker = new LinksOperationInvoker();
        ActuatorPathMappingContext ctx = new ActuatorPathMappingContext(
                invoker, "/actuator", null, null,
                null, mediaTypes, "/actuator", WebServerNamespace.SERVER);

        ApiVersion version = invokeResolveApiVersion(ctx, null);
        assertEquals(ApiVersion.LATEST, version);
    }

    @Test
    void resolveApiVersion_emptyAcceptHeaders() throws Exception {
        LinksOperationInvoker invoker = new LinksOperationInvoker();
        ActuatorPathMappingContext ctx = new ActuatorPathMappingContext(
                invoker, "/actuator", null, null,
                null, mediaTypes, "/actuator", WebServerNamespace.SERVER);

        ApiVersion version = invokeResolveApiVersion(ctx, Collections.<MediaType>emptyList());
        assertEquals(ApiVersion.LATEST, version);
    }

    @Test
    void getOperation_returnsOperationWhenSet() {
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(null, predicate, Collections.emptyList());
        ActuatorPathMappingContext ctx = new ActuatorPathMappingContext(
                invoker, "/actuator/health", null, predicate,
                null, mediaTypes, "/actuator", WebServerNamespace.SERVER);

        assertNull(ctx.getOperation());
    }

    @Test
    void getPredicate_returnsPredicateForOperationEndpoint() {
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(null, predicate, Collections.emptyList());
        ActuatorPathMappingContext ctx = new ActuatorPathMappingContext(
                invoker, "/actuator/health", null, predicate,
                null, mediaTypes, "/actuator", WebServerNamespace.SERVER);

        assertSame(predicate, ctx.getPredicate());
    }

    @SuppressWarnings("unchecked")
    private static ApiVersion invokeResolveApiVersion(ActuatorPathMappingContext ctx, List<MediaType> accepts) throws Exception {
        Method method = ActuatorPathMappingContext.class.getDeclaredMethod("resolveApiVersion", List.class);
        method.setAccessible(true);
        return (ApiVersion) method.invoke(ctx, accepts);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeBuildActuatorArguments(ActuatorPathMappingContext ctx) throws Exception {
        Method method = ActuatorPathMappingContext.class.getDeclaredMethod("buildActuatorArguments", WebServerHttpRequest.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(ctx, request);
    }
}
package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.endpoint.ApiVersion;
import org.springframework.boot.actuate.endpoint.InvocationContext;
import org.springframework.boot.actuate.endpoint.web.EndpointLinksResolver;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.WebEndpointHttpMethod;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.WebOperation;
import org.springframework.boot.actuate.endpoint.web.WebOperationRequestPredicate;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.net.URI;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
        WebOperation operation = mock(WebOperation.class);
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(operation, predicate, Collections.emptyList());
        ActuatorPathMappingContext ctx = new ActuatorPathMappingContext(
                invoker, "/actuator/health", operation, predicate,
                null, mediaTypes, "/actuator", WebServerNamespace.SERVER);

        assertSame(operation, ctx.getOperation(), "构造传入的 WebOperation 应可由 getOperation 取回");
    }

    @Test
    void getOperation_linksEndpoint_returnsNull() {
        LinksOperationInvoker invoker = new LinksOperationInvoker();
        ActuatorPathMappingContext ctx = new ActuatorPathMappingContext(
                invoker, "/actuator", null, null,
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

    /* ==================== invoke 全路径 ==================== */

    @Test
    void invoke_linksEndpoint_resolvesLinks() throws Throwable {
        EndpointLinksResolver linksResolver = mock(EndpointLinksResolver.class);
        when(request.getURI()).thenReturn(java.net.URI.create("http://localhost/actuator"));
        Map<String, org.springframework.boot.actuate.endpoint.web.Link> links = Collections.singletonMap("self",
                new org.springframework.boot.actuate.endpoint.web.Link("/actuator"));
        when(linksResolver.resolveLinks("http://localhost/actuator")).thenReturn(links);
        LinksOperationInvoker invoker = new LinksOperationInvoker();
        ActuatorPathMappingContext ctx = new ActuatorPathMappingContext(
                invoker, "/actuator", linksResolver, mediaTypes, "/actuator", WebServerNamespace.SERVER);

        Object result = ctx.invoke(new Object[0], request, response);

        assertInstanceOf(Map.class, result);
        assertSame(links, ((Map<?, ?>) result).get("_links"));
    }

    @Test
    void invoke_operationWebEndpointResponse_setsStatusAndContentType() throws Throwable {
        WebOperation operation = mock(WebOperation.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        when(request.getHeaders()).thenReturn(headers);
        when(request.getRequestContext()).thenReturn(mock(RequestContext.class));
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        when(operation.invoke(any(InvocationContext.class)))
                .thenReturn(new WebEndpointResponse<>("hello", HttpStatus.CREATED.value(), MediaType.APPLICATION_JSON));
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(operation, predicate, Collections.emptyList());
        ActuatorPathMappingContext ctx = new ActuatorPathMappingContext(
                invoker, "/actuator/health", operation, predicate,
                null, mediaTypes, "/actuator", WebServerNamespace.SERVER);

        Object result = ctx.invoke(new Object[0], request, response);

        assertEquals("hello", result, "WebEndpointResponse 应解包为 body");
        verify(response).setStatusCode(HttpStatus.CREATED);
    }

    @Test
    void invoke_operationWebEndpointResponse_defaultStatus_notModified() throws Throwable {
        WebOperation operation = mock(WebOperation.class);
        HttpHeaders headers = new HttpHeaders();
        when(request.getHeaders()).thenReturn(headers);
        when(request.getRequestContext()).thenReturn(mock(RequestContext.class));
        when(operation.invoke(any(InvocationContext.class)))
                .thenReturn(new WebEndpointResponse<>("plain"));
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(operation, predicate, Collections.emptyList());
        ActuatorPathMappingContext ctx = new ActuatorPathMappingContext(
                invoker, "/actuator/health", operation, predicate,
                null, mediaTypes, "/actuator", WebServerNamespace.SERVER);

        Object result = ctx.invoke(new Object[0], request, response);

        assertEquals("plain", result);
        verify(response, never()).setStatusCode(any());
    }

    @Test
    void buildActuatorArguments_withUriVariablesAndParams() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        when(request.getHeaders()).thenReturn(headers);
        RequestContext requestContext = mock(RequestContext.class);
        when(request.getRequestContext()).thenReturn(requestContext);
        WebOperationRequestPredicate pred = new WebOperationRequestPredicate(
                "/rooms/{roomId}", WebEndpointHttpMethod.GET,
                Collections.emptyList(), Collections.singletonList("application/json"));
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(null, pred, Collections.emptyList());
        ActuatorPathMappingContext ctx = new ActuatorPathMappingContext(
                invoker, "/actuator/health", null, pred,
                null, mediaTypes, "/actuator", WebServerNamespace.SERVER);
        // 注入 uriVariables 到 request context
        Map<String, String> uriVars = new java.util.HashMap<>();
        uriVars.put("roomId", "42");
        when(requestContext.getAttribute(io.springperf.web.core.mapping.route.PathPatternRouter.URI_VARIABLE_MAP_ATTRIBUTE)).thenReturn(uriVars);
        Map<String, String[]> params = new java.util.HashMap<>();
        params.put("verbose", new String[]{"true"});
        when(request.getParameterMapArray()).thenReturn(params);

        Map<String, Object> args = invokeBuildActuatorArguments(ctx);

        assertEquals("42", args.get("roomId"), "uriVariables 应注入参数");
        assertEquals("true", args.get("verbose"), "单值参数应展开为字符串");
        assertTrue(args.containsKey("Accept"));
    }
}

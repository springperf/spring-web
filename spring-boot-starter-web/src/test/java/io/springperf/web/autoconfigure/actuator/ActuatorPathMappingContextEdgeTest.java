package io.springperf.web.autoconfigure.actuator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.ApiVersion;
import org.springframework.boot.actuate.endpoint.InvocationContext;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.WebEndpointHttpMethod;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.WebOperation;
import org.springframework.boot.actuate.endpoint.web.WebOperationRequestPredicate;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

/** Actuator 参数构建与响应解包的边界分支。 */
class ActuatorPathMappingContextEdgeTest {

    private final EndpointMediaTypes mediaTypes = EndpointMediaTypes.DEFAULT;
    private final WebOperationRequestPredicate predicate = new WebOperationRequestPredicate("/health",
            WebEndpointHttpMethod.GET, Collections.emptyList(), Collections.singletonList("application/json"));

    private WebServerHttpRequest request;
    private WebServerHttpResponse response;
    private WebOperation operation;

    @BeforeEach
    void setUp() {
        request = mock(WebServerHttpRequest.class);
        response = mock(WebServerHttpResponse.class);
        operation = mock(WebOperation.class);
        HttpHeaders headers = new HttpHeaders();
        lenient().when(request.getHeaders()).thenReturn(headers);
        lenient().when(request.getRequestContext()).thenReturn(mock(RequestContext.class));
        lenient().when(response.getHeaders()).thenReturn(new HttpHeaders());
    }

    private ActuatorPathMappingContext context() {
        OperationHandlerInvoker invoker = new OperationHandlerInvoker(operation, predicate, Collections.emptyList());
        return new ActuatorPathMappingContext(invoker, "/actuator/health", operation, predicate, null, mediaTypes,
                "/actuator", WebServerNamespace.SERVER);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildArguments(ActuatorPathMappingContext ctx, WebServerHttpRequest req)
            throws Exception {
        Method m = ActuatorPathMappingContext.class.getDeclaredMethod("buildActuatorArguments",
                WebServerHttpRequest.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(ctx, req);
    }

    private static ApiVersion resolveVersion(ActuatorPathMappingContext ctx, List<MediaType> accepts) throws Exception {
        Method m = ActuatorPathMappingContext.class.getDeclaredMethod("resolveApiVersion", List.class);
        m.setAccessible(true);
        return (ApiVersion) m.invoke(ctx, accepts);
    }

    @Test
    void multiValueParameterIsPassedAsStringArray() throws Exception {
        Map<String, String[]> params = new LinkedHashMap<>();
        params.put("tag", new String[] { "a", "b" });
        when(request.getParameterMapArray()).thenReturn(params);

        Map<String, Object> args = buildArguments(context(), request);

        assertInstanceOf(String[].class, args.get("tag"));
        assertArrayEquals(new String[] { "a", "b" }, (String[]) args.get("tag"));
        assertEquals(Collections.emptyList(), args.get("Accept"));
    }

    @Test
    void nullAndEmptyParameterValuesAreSkipped() throws Exception {
        Map<String, String[]> params = new LinkedHashMap<>();
        params.put("nullValues", null);
        params.put("emptyValues", new String[0]);
        params.put("single", new String[] { "only" });
        when(request.getParameterMapArray()).thenReturn(params);

        Map<String, Object> args = buildArguments(context(), request);

        assertFalse(args.containsKey("nullValues"));
        assertFalse(args.containsKey("emptyValues"));
        assertEquals("only", args.get("single"));
    }

    @Test
    void nullParameterMapIsTolerated() throws Exception {
        when(request.getParameterMapArray()).thenReturn(null);

        Map<String, Object> args = buildArguments(context(), request);

        assertEquals(Collections.singletonMap("Accept", Collections.emptyList()), args);
    }

    @Test
    void incompatibleAcceptFallsBackToLatestVersion() throws Exception {
        assertEquals(ApiVersion.LATEST,
                resolveVersion(context(), Collections.singletonList(MediaType.APPLICATION_PDF)));
        assertEquals(ApiVersion.LATEST, resolveVersion(context(), null));
    }

    @Test
    void nonStandardResponseStatusIsNotMappedToHttpStatus() throws Throwable {
        when(operation.invoke(any(InvocationContext.class))).thenReturn(new WebEndpointResponse<>("body", 599));

        Object result = context().invoke(new Object[0], request, response);

        assertEquals("body", result);
        verify(response, never()).setStatusCode(any());
    }

    @Test
    void responseContentTypeIsPropagatedToHeaders() throws Throwable {
        when(operation.invoke(any(InvocationContext.class)))
                .thenReturn(new WebEndpointResponse<>("body", 200, MediaType.TEXT_PLAIN));

        Object result = context().invoke(new Object[0], request, response);

        assertEquals("body", result);
        assertEquals(MediaType.TEXT_PLAIN, response.getHeaders().getContentType());
    }
}

package io.springperf.web.autoconfigure.actuator;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.invoker.CustomInvoker;
import io.springperf.web.core.mapping.route.PathPatternRouter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.endpoint.*;
import org.springframework.boot.actuate.endpoint.http.ApiVersion;
import org.springframework.boot.actuate.endpoint.web.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.*;

/**
 * 为 Actuator 端点操作定制的 {@link PathMappingContext}。
 */
@Slf4j
public class PerfActuatorPathMappingContext extends PathMappingContext {

    private final WebOperation operation;
    private final WebOperationRequestPredicate predicate;
    private final EndpointLinksResolver linksResolver;
    private final EndpointMediaTypes endpointMediaTypes;
    private final String basePath;
    private final boolean linksEndpoint;

    public PerfActuatorPathMappingContext(CustomInvoker invoker, String pathRule,
                                          WebOperation operation, WebOperationRequestPredicate predicate,
                                          EndpointLinksResolver linksResolver,
                                          EndpointMediaTypes endpointMediaTypes, String basePath) {
        super(invoker, pathRule);
        this.operation = operation;
        this.predicate = predicate;
        this.linksResolver = linksResolver;
        this.endpointMediaTypes = endpointMediaTypes;
        this.basePath = basePath;
        this.linksEndpoint = false;
    }

    PerfActuatorPathMappingContext(CustomInvoker invoker, String pathRule,
                                    EndpointLinksResolver linksResolver,
                                    EndpointMediaTypes endpointMediaTypes, String basePath) {
        super(invoker, pathRule);
        this.operation = null;
        this.predicate = null;
        this.linksResolver = linksResolver;
        this.endpointMediaTypes = endpointMediaTypes;
        this.basePath = basePath;
        this.linksEndpoint = true;
    }

    @Override
    public Object invoke(Object[] args, WebServerHttpRequest request, WebServerHttpResponse response) throws Throwable {
        Object result = linksEndpoint ? resolveLinks(request) : invokeOperation(request, response);
        if (result instanceof WebEndpointResponse) {
            WebEndpointResponse<?> wer = (WebEndpointResponse<?>) result;
            int statusCode = wer.getStatus();
            if (statusCode > 0 && statusCode != WebEndpointResponse.STATUS_OK) {
                HttpStatus httpStatus = HttpStatus.resolve(statusCode);
                if (httpStatus != null) response.setStatusCode(httpStatus);
            }
            if (wer.getContentType() != null)
                response.getHeaders().setContentType(MediaType.parseMediaType(wer.getContentType().toString()));
            result = wer.getBody();
        }
        return result;
    }

    private Object resolveLinks(WebServerHttpRequest request) {
        return Collections.singletonMap("_links", linksResolver.resolveLinks(request.getUriStrWithQuery()));
    }

    private Object invokeOperation(WebServerHttpRequest request, WebServerHttpResponse response) {
        Map<String, Object> arguments = buildActuatorArguments(request);
        ApiVersion apiVersion = resolveApiVersion(request.getHeaders().getAccept());
        return operation.invoke(new InvocationContext(SecurityContext.NONE, arguments,
                OperationArgumentResolver.of(ApiVersion.class, () -> apiVersion)));
    }

    private ApiVersion resolveApiVersion(List<MediaType> acceptHeaders) {
        if (acceptHeaders == null || acceptHeaders.isEmpty()) return ApiVersion.LATEST;
        for (MediaType accepted : acceptHeaders)
            for (String producedStr : endpointMediaTypes.getProduced()) {
                MediaType produced = MediaType.parseMediaType(producedStr);
                if (accepted.isCompatibleWith(produced))
                    return producedStr.contains("v2") ? ApiVersion.V2 :
                           producedStr.contains("v3") ? ApiVersion.V3 : ApiVersion.LATEST;
            }
        return ApiVersion.LATEST;
    }

    private Map<String, Object> buildActuatorArguments(WebServerHttpRequest request) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        Map<String, String> uriVariables = PathPatternRouter.getUriVariableMap(request);
        if (uriVariables != null && !uriVariables.isEmpty()) arguments.putAll(uriVariables);
        Map<String, String[]> parameterMap = request.getParameterMapArray();
        if (parameterMap != null && !parameterMap.isEmpty())
            for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
                String[] values = entry.getValue();
                if (values != null && values.length > 0)
                    arguments.put(entry.getKey(), values.length == 1 ? values[0] : values);
            }
        if (predicate != null) {
            String matchAllRemaining = predicate.getMatchAllRemainingPathSegmentsVariable();
            if (matchAllRemaining != null && uriVariables != null) {
                String remaining = uriVariables.get(matchAllRemaining);
                if (remaining != null) arguments.put(matchAllRemaining, remaining);
            }
        }
        arguments.put("Accept", request.getHeaders().getAccept());
        return arguments;
    }

    public WebOperation getOperation() { return operation; }
    public WebOperationRequestPredicate getPredicate() { return predicate; }
}
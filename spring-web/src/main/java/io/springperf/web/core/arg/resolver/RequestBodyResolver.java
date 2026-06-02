package io.springperf.web.core.arg.resolver;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.codec.HttpBodyCodecRegistry;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.lang.reflect.Type;

public class RequestBodyResolver extends AbstractSupportOptionalResolver implements StaticArgumentResolver {
    private final WebContext webContext;
    private final boolean required;
    private final HttpBodyCodecRegistry httpBodyCodecRegistry;
    private final Type targetType;

    public RequestBodyResolver(WebContext webContext, MappingHandlerMethod mappingContext, MethodParameter parameter, boolean required) {
        super(mappingContext, parameter);
        this.webContext = webContext;
        this.required = isOptional ? false : required;
        this.httpBodyCodecRegistry = webContext.getWebComponent(HttpBodyCodecRegistry.class);
        this.targetType = parameter.getGenericParameterType();
    }

    @Override
    protected Object doResolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        try {
            return httpBodyCodecRegistry.readBody(targetType, parameter, request, request);
        } catch (Exception e) {
            if (required) {
                throw new HttpMessageNotReadableException("Failed to parse @RequestBody data", e);
            } else {
                return null;
            }
        }
    }
}

package io.springperf.web.core.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.server.ServerHttpResponse;

public class ResponseResolverProvider implements StaticArgumentResolverProvider {

    private final StaticArgumentResolver httpResponseResolver = (request, response) -> response;

    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        Class<?> parameterType = parameter.getParameterType();
        if (WebServerHttpResponse.class.equals(parameterType) || ServerHttpResponse.class.equals(parameterType)
                || HttpOutputMessage.class.equals(parameterType)) {
            return true;
        }
        return false;
    }

    @Override
    public StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        return httpResponseResolver;
    }
}

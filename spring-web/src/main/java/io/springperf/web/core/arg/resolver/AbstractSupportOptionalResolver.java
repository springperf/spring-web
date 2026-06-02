package io.springperf.web.core.arg.resolver;

import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;

import java.util.Optional;

public abstract class AbstractSupportOptionalResolver implements StaticArgumentResolver {

    protected final MappingHandlerMethod mappingContext;

    protected final MethodParameter parameter;
    protected final Class<?> paramType;
    protected final boolean isOptional;

    public AbstractSupportOptionalResolver(MappingHandlerMethod mappingContext, MethodParameter parameter) {
        this.mappingContext = mappingContext;
        this.parameter = parameter;
        MethodParameter nestedParameter = parameter.nestedIfOptional();
        this.paramType = nestedParameter.getNestedParameterType();
        this.isOptional = nestedParameter.isOptional();
    }

    protected abstract Object doResolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception;

    @Override
    public Object resolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        Object arg = doResolveArgument(request, response);
        if (isOptional) {
            if (isEmpty(arg)) {
                arg = Optional.empty();
            } else {
                arg = Optional.of(arg);
            }
        }
        return arg;
    }

    protected boolean isEmpty(Object arg) {
        return arg == null;
    }
}

package io.springperf.web.core.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.resolver.AbstractNamedValueNullableResolver;
import io.springperf.web.core.arg.resolver.AbstractSupportOptionalResolver;
import io.springperf.web.core.arg.resolver.MultiValueMapResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.route.PathPatternRouter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;

import java.lang.annotation.Annotation;

public class PathVariableResolverProvider extends AbstractSupportResolverProvider {

    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        return parameter.hasParameterAnnotation(PathVariable.class);
    }

    @Override
    protected MultiValueMapResolver getMultiValueMapResolver() {
        return null;
    }

    @Override
    protected Class<? extends Annotation>[] supportAnnotationClass() {
        return new Class[]{PathVariable.class};
    }

    @Override
    protected boolean isMultiValueMap(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        return false;
    }

    @Override
    protected boolean isCollection(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        return false;
    }

    @Override
    protected StaticArgumentResolver getSimpleMapArgumentResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        return new AbstractSupportOptionalResolver(mappingContext, parameter) {
            @Override
            protected Object doResolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) {
                return PathPatternRouter.getUriVariableMap(request);
            }
        };
    }

    @Override
    protected StaticArgumentResolver getSingleValueArgumentResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        return new AbstractNamedValueNullableResolver(webContext, mappingContext, parameter, supportAnnotationClass()) {
            @Override
            protected Object resolveByName(WebServerHttpRequest request, WebServerHttpResponse response) {
                return PathPatternRouter.getUriVariableMap(request).get(name);
            }
        };
    }
}

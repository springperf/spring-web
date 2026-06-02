package io.springperf.web.core.arg.provider;

import io.springperf.web.core.arg.resolver.MultiValueMapResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.annotation.Annotation;

public class RequestParamResolverProvider extends AbstractSupportResolverProvider implements StaticArgumentResolverProvider {
    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        return parameter.hasParameterAnnotation(RequestParam.class);
    }

    @Override
    protected MultiValueMapResolver getMultiValueMapResolver() {
        return ((parameter, mappingContext, request, response) -> request.getParameterMap());
    }

    @Override
    protected Class<? extends Annotation>[] supportAnnotationClass() {
        return new Class[]{RequestParam.class};
    }
}

package io.springperf.web.core.arg.provider;

import io.springperf.web.core.arg.resolver.MultiValueMapResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestHeader;

import java.lang.annotation.Annotation;

public class RequestHeaderResolverProvider extends AbstractSupportResolverProvider implements StaticArgumentResolverProvider {
    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        return parameter.hasParameterAnnotation(RequestHeader.class);
    }

    @Override
    protected MultiValueMapResolver getMultiValueMapResolver() {
        return ((parameter, mappingContext, request, response) -> request.getHeaders());
    }


    @Override
    protected Class<? extends Annotation>[] supportAnnotationClass() {
        return new Class[]{RequestHeader.class};
    }
}

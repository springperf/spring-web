package io.springperf.web.core.arg.provider;

import java.lang.annotation.Annotation;

import org.springframework.core.MethodParameter;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestHeader;

import io.springperf.web.core.arg.resolver.MultiValueMapResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;

public class RequestHeaderResolverProvider extends AbstractSupportResolverProvider
        implements StaticArgumentResolverProvider {
    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        return parameter.hasParameterAnnotation(RequestHeader.class);
    }

    @Override
    protected MultiValueMapResolver getMultiValueMapResolver() {
        // 框架的请求头由 WebHttpHeaders 承载，它以自身实现 MultiValueMap（Spring 6 靠继承父类，
        // Spring 7 靠本类自带实现），故此处显式转型即可跨版本 —— 不能直接返回 HttpHeaders，
        // 因为 Spring 7 的 HttpHeaders 已不是 MultiValueMap（编译期即报错）。
        return ((parameter, mappingContext, request, response) -> (MultiValueMap<String, String>) request.getHeaders());
    }

    @Override
    protected Class<? extends Annotation>[] supportAnnotationClass() {
        return new Class[] { RequestHeader.class };
    }
}

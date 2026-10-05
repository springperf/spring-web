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
        // Spring 7 的 HttpHeaders 不再实现 MultiValueMap，故用 asMultiValueMap() 取内部 map 视图
        // （零拷贝）。不能强转：那只有在本框架自建的 WebHttpHeaders 上成立，一旦请求头是
        // 别的 HttpHeaders（包装类、测试替身、代理）就会 ClassCastException。
        return ((parameter, mappingContext, request, response) -> request.getHeaders().asMultiValueMap());
    }

    @Override
    protected Class<? extends Annotation>[] supportAnnotationClass() {
        return new Class[] { RequestHeader.class };
    }
}

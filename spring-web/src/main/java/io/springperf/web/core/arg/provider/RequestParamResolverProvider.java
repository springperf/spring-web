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

    /**
     * {@code @RequestParam} 单值解析改走请求侧直查 {@code request.getParameter(name)}：
     * 与 {@code getParameterMap().getFirst(name)} 同义，但纯查询请求下可命中「查询串直扫」快路径
     * （无 body 且无 {@code % + ; #} 转义时零哈希零分配；其余情况内部自动回退通用路径，
     * 参数上限校验等语义不变）。
     */
    @Override
    protected boolean useRequestSideParameterLookup() {
        return true;
    }

    @Override
    protected Class<? extends Annotation>[] supportAnnotationClass() {
        return new Class[]{RequestParam.class};
    }
}

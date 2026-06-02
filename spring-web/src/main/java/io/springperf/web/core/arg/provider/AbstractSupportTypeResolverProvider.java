package io.springperf.web.core.arg.provider;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.util.MetaUtils;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;

import java.lang.annotation.Annotation;

public abstract class AbstractSupportTypeResolverProvider<T> extends AbstractSupportResolverProvider<T> implements StaticArgumentResolverProvider {
    protected abstract Class<?> supportType();

    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        Class<?> supportType = supportType();
        Class<?> paramType = parameter.getNestedParameterType();
        return supportType == paramType
                || supportType == paramType.getComponentType()
                || supportType == MetaUtils.getCollectionParameterType(parameter)
                || supportType == MetaUtils.getMapParameterType(parameter);
    }

    protected Class<? extends Annotation>[] supportAnnotationClass() {
        return new Class[]{RequestParam.class, ModelAttribute.class, RequestPart.class};
    }

    @Override
    public int getOrder() {
        return -10000;
    }
}

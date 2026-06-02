package io.springperf.web.core.arg.provider;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.databinder.WebDataBinderRegistry;
import io.springperf.web.core.arg.resolver.ModelAttributeResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.util.MetaUtils;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.ModelAttribute;

public class ModelAttributeResolverProvider extends BaseWebComponent implements StaticArgumentResolverProvider {

    protected WebDataBinderRegistry webDataBinderRegistry;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        webDataBinderRegistry = webContext.getWebComponentWithDefault(WebDataBinderRegistry.class, new WebDataBinderRegistry());
    }

    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        return parameter.hasParameterAnnotation(ModelAttribute.class);
    }

    @Override
    public StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        ModelAttribute modelAttribute = parameter.getParameterAnnotation(ModelAttribute.class);
        return new ModelAttributeResolver(webDataBinderRegistry.getWebDataBinderFactory(mappingContext), webContext, mappingContext, parameter,
                MetaUtils.getParameterName(parameter, ModelAttribute.class), modelAttribute == null ? true : modelAttribute.binding());
    }
}

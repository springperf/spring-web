package io.springperf.web.core.arg.provider;

import io.springperf.web.context.WebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import org.springframework.core.MethodParameter;

public interface StaticArgumentResolverProvider extends WebComponent {

    boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext);

    StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext);
}


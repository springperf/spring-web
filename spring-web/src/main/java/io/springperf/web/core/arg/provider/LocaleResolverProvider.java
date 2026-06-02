package io.springperf.web.core.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.resolver.AbstractSupportOptionalResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.MethodParameter;

import java.util.Locale;

public class LocaleResolverProvider implements StaticArgumentResolverProvider {

    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        Class<?> parameterType = parameter.getParameterType();
        if (Locale.class.equals(parameterType)) {
            return true;
        }
        return false;
    }

    @Override
    public StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        return new AbstractSupportOptionalResolver(mappingContext, parameter) {
            @Override
            protected Object doResolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
                return LocaleContextHolder.getLocale();
            }
        };
    }
}

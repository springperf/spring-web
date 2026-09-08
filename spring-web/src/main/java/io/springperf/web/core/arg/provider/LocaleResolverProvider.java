package io.springperf.web.core.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.resolver.AbstractSupportOptionalResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.context.i18n.LocaleContext;
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
                // 优先尊重当前线程已绑定的 Locale（拦截器 / ControllerAdvice 通过
                // LocaleContextHolder.setLocale 覆盖时生效）；未绑定时按请求 Accept-Language 解析。
                // 不写 ThreadLocal（无线程池残留、零固定开销），仅声明 Locale 参数时才会解析。
                LocaleContext localeContext = LocaleContextHolder.getLocaleContext();
                if (localeContext != null) {
                    return localeContext.getLocale();
                }
                return request.getLocale();
            }
        };
    }
}

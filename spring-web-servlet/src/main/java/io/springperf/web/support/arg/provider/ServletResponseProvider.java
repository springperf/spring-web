package io.springperf.web.support.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.provider.StaticArgumentResolverProvider;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.support.servlet.ServletAttribute;
import javax.servlet.ServletResponse;
import org.springframework.core.MethodParameter;

/**
 * 解析 {@code Servlet.service(ServletRequest, ServletResponse)} 的 {@link ServletResponse}
 * 参数。与 {@link HttpServletResponseProvider}（精确匹配 {@code HttpServletResponse}）互补，
 * 此处精确匹配父接口 {@code ServletResponse}。
 */
public class ServletResponseProvider implements StaticArgumentResolverProvider {

    private final StaticArgumentResolver resolver = (request, response) ->
            ServletAttribute.getAdapterContext(request, response).getResponse();

    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        return ServletResponse.class.equals(parameter.getParameterType());
    }

    @Override
    public StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        return resolver;
    }
}

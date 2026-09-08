package io.springperf.web.support.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.provider.StaticArgumentResolverProvider;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.support.servlet.ServletAttribute;
import javax.servlet.ServletRequest;
import org.springframework.core.MethodParameter;

/**
 * 瑙ｆ瀽 {@code Servlet.service(ServletRequest, ServletResponse)} 鐨?{@link ServletRequest}
 * 鍙傛暟銆備笌 {@link HttpServletRequestProvider}锛堢簿纭尮閰?{@code HttpServletRequest}锛変簰琛ワ紝
 * 姝ゅ绮剧‘鍖归厤鐖舵帴鍙?{@code ServletRequest}銆?
 */
public class ServletRequestProvider implements StaticArgumentResolverProvider {

    private final StaticArgumentResolver resolver = (request, response) ->
            ServletAttribute.getAdapterContext(request, response).getRequest();

    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        return ServletRequest.class.equals(parameter.getParameterType());
    }

    @Override
    public StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        return resolver;
    }
}

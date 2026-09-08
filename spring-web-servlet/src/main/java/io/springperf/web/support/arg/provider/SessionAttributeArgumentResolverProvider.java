package io.springperf.web.support.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.provider.StaticArgumentResolverProvider;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.support.servlet.ServletAttribute;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.springframework.core.MethodParameter;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.SessionAttribute;

/**
 * 解析 {@link SessionAttribute}（单数）注解参数：从当前请求的 session 中按名读取
 * 单个属性注入方法参数（只读，不触发 session 创建）。
 * <p>语义对齐 Spring MVC {@code SessionAttributeMethodArgumentResolver}：
 * <ul>
 *   <li>属性名取注解 {@code name}/{@code value}，为空则用参数名；</li>
 *   <li>{@code required=true}（默认）且 session 中不存在时抛 {@link ServletRequestBindingException}；</li>
 *   <li>{@code required=false} 且不存在（或无 session）时返回 {@code null}。</li>
 * </ul>
 */
public class SessionAttributeArgumentResolverProvider implements StaticArgumentResolverProvider {

    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        return parameter.hasParameterAnnotation(SessionAttribute.class);
    }

    @Override
    public StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext,
                                              WebContext webContext) {
        SessionAttribute annotation = parameter.getParameterAnnotation(SessionAttribute.class);
        String name = StringUtils.hasText(annotation.name()) ? annotation.name() : parameter.getParameterName();
        final boolean required = annotation.required();
        final String attributeName = name;
        return (request, response) -> {
            HttpServletRequest servletRequest = ServletAttribute.getAdapterContext(request, response).getRequest();
            HttpSession session = servletRequest.getSession(false);
            Object value = (session == null) ? null : session.getAttribute(attributeName);
            if (value == null && required) {
                throw new ServletRequestBindingException(
                        "Required session attribute '" + attributeName + "' is not present");
            }
            return value;
        };
    }
}

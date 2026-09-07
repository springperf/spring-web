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
 * 瑙ｆ瀽 {@link SessionAttribute}锛堝崟鏁帮級娉ㄨВ鍙傛暟锛氫粠褰撳墠璇锋眰鐨?session 涓寜鍚嶈鍙?
 * 鍗曚釜灞炴€ф敞鍏ユ柟娉曞弬鏁帮紙鍙锛屼笉瑙﹀彂 session 鍒涘缓锛夈€?
 * <p>璇箟瀵归綈 Spring MVC {@code SessionAttributeMethodArgumentResolver}锛?
 * <ul>
 *   <li>灞炴€у悕鍙栨敞瑙?{@code name}/{@code value}锛屼负绌哄垯鐢ㄥ弬鏁板悕锛?/li>
 *   <li>{@code required=true}锛堥粯璁わ級涓?session 涓笉瀛樺湪鏃舵姏 {@link ServletRequestBindingException}锛?/li>
 *   <li>{@code required=false} 涓斾笉瀛樺湪锛堟垨鏃?session锛夋椂杩斿洖 {@code null}銆?/li>
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

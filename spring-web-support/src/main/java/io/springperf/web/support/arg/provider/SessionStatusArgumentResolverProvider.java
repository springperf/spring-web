package io.springperf.web.support.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.provider.StaticArgumentResolverProvider;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.support.model.SessionAttributesInterceptor;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.SessionStatus;

/**
 * 解析 {@link SessionStatus} 方法参数：返回绑定到本次请求的 {@code SessionStatus} 实例，
 * 供 {@code @SessionAttributes} 声明的方法调用 {@code setComplete()} 时在
 * {@code SessionAttributesInterceptor} 中清理 session 属性。
 */
public class SessionStatusArgumentResolverProvider implements StaticArgumentResolverProvider {

    @Override
    public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
        return SessionStatus.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public StaticArgumentResolver getResolver(MethodParameter parameter,
                                              MappingHandlerMethod mappingContext,
                                              WebContext webContext) {
        // 启动期一次性解析并缓存引用，请求热路径零查找
        SessionAttributesInterceptor interceptor = webContext.getBeanFromCtx(SessionAttributesInterceptor.class);
        if (interceptor == null) {
            interceptor = new SessionAttributesInterceptor();
        }
        final SessionAttributesInterceptor ref = interceptor;
        return (request, response) -> ref.getOrCreateSessionStatus(request, response);
    }
}
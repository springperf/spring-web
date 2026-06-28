package io.springperf.web.support.mvc.retval;

import io.springperf.web.context.WebComponentWrapper;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.retval.ReturnValueResolver;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.PerfHttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Adapts a Spring MVC {@link HandlerMethodReturnValueHandler} to the framework's
 * {@link ReturnValueResolver} interface.
 *
 * <p>The adapter creates Servlet API wrappers around the framework's request/response
 * objects so that existing Spring return value handlers can be used without modification.
 *
 * <p>Order is resolved via {@link WebComponentWrapper}:
 * <ol>
 *   <li>If the wrapped handler has {@link org.springframework.core.annotation.Order @Order}
 *       or implements {@link org.springframework.core.Ordered Ordered}, that value is used.</li>
 *   <li>Otherwise falls back to {@link #defaultOrderPriority()} = {@code LOWEST_PRECEDENCE - 10000}.</li>
 * </ol>
 * The {@link io.springperf.web.support.mvc.config.WebMvcConfigurerBridge} may override
 * the order to a higher priority when no custom order is set.
 */
public class SpringHandlerMethodReturnValueHandlerAdapter
        extends WebComponentWrapper<HandlerMethodReturnValueHandler>
        implements ReturnValueResolver {

    public SpringHandlerMethodReturnValueHandlerAdapter(HandlerMethodReturnValueHandler handler) {
        super(handler, "springRetValHandler_" + handler.getClass().getName());
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        return getComponent().supportsReturnType(returnType);
    }

    @Override
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        if (returnValue == null) {
            return false;
        }
        PathMappingContext mappingContext = PathMappingContext.get(req);
        if (mappingContext != null) {
            MethodParameter returnType = new MethodParameter(mappingContext.getMethod(), -1);
            return getComponent().supportsReturnType(returnType);
        }
        return true;
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType,
                                   WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        HttpServletRequest servletRequest = new PerfHttpServletRequest(request);
        HttpServletResponse servletResponse = new PerfHttpServletResponse(response);
        NativeWebRequest webRequest = new ServletWebRequest(servletRequest, servletResponse);
        ModelAndViewContainer mavContainer = new ModelAndViewContainer();
        mavContainer.setRequestHandled(true);
        getComponent().handleReturnValue(returnValue, returnType, mavContainer, webRequest);
    }
}
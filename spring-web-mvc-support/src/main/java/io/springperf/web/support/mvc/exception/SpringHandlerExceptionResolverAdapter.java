package io.springperf.web.support.mvc.exception;

import io.springperf.web.context.WebComponentWrapper;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.PerfHttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.method.HandlerMethod;

/**
 * Adapts a Spring MVC {@link org.springframework.web.servlet.HandlerExceptionResolver}
 * to the framework's {@link io.springperf.web.core.exception.HandlerExceptionResolver} interface.
 *
 * <p>The adapter creates Servlet API wrappers around the framework's request/response
 * objects and invokes the Spring exception resolver. A non-null {@code ModelAndView}
 * return value indicates the exception was handled.
 *
 * <p>Order is resolved via {@link WebComponentWrapper}:
 * <ol>
 *   <li>If the wrapped resolver has {@link org.springframework.core.annotation.Order @Order}
 *       or implements {@link org.springframework.core.Ordered Ordered}, that value is used.</li>
 *   <li>Otherwise falls back to {@link #defaultOrderPriority()} = {@code LOWEST_PRECEDENCE - 10000}.</li>
 * </ol>
 * The {@link io.springperf.web.support.mvc.config.WebMvcConfigurerBridge} may override
 * the order to a higher priority when no custom order is set.
 */
@Slf4j
public class SpringHandlerExceptionResolverAdapter
        extends WebComponentWrapper<org.springframework.web.servlet.HandlerExceptionResolver>
        implements io.springperf.web.core.exception.HandlerExceptionResolver {

    public SpringHandlerExceptionResolverAdapter(org.springframework.web.servlet.HandlerExceptionResolver resolver) {
        super(resolver, "springExceptionResolver_" + resolver.getClass().getName());
    }

    @Override
    public boolean resolveException(WebServerHttpRequest request, WebServerHttpResponse response,
                                    HandlerMethod handler, Throwable ex) {
        try {
            HttpServletRequest servletRequest = new PerfHttpServletRequest(request);
            HttpServletResponse servletResponse = new PerfHttpServletResponse(response);
            Exception exception = ex instanceof Exception ? (Exception) ex : new RuntimeException(ex);
            Object handlerObj = handler != null ? handler.getBean() : null;
            log.debug("SpringHandlerExceptionResolverAdapter.resolveException called: ex={}, handler={}, resolver={}",
                    ex.getClass().getSimpleName(), handlerObj, getComponent().getClass().getSimpleName());
            Object mv = getComponent().resolveException(servletRequest, servletResponse, handlerObj, exception);
            log.debug("SpringHandlerExceptionResolverAdapter resolved: mv={}", mv);
            return mv != null;
        } catch (Exception e) {
            log.warn("Spring HandlerExceptionResolver {} threw exception", getComponent().getClass().getName(), e);
            return false;
        }
    }
}
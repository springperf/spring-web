package io.springperf.webtest.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * A custom {@link HandlerExceptionResolver} registered via
 * {@link org.springframework.web.servlet.config.annotation.WebMvcConfigurer#extendHandlerExceptionResolvers}.
 * <p>
 * Handles {@link CustomE2eBridgeException} by returning a plain-text response
 * with status 400 and body "bridge-exception-handled".
 */
public class CustomE2eExceptionResolver implements HandlerExceptionResolver, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CustomE2eExceptionResolver.class);

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception ex) {
        if (ex instanceof CustomE2eBridgeException) {
            log.info("CustomE2eExceptionResolver handling CustomE2eBridgeException");
            try {
                response.setStatus(400);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("bridge-exception-handled");
                response.getWriter().flush();
            } catch (Exception e) {
                log.warn("Failed to write exception response", e);
            }
            return new ModelAndView();
        }
        return null;
    }
}

package io.springperf.webtest.bridge;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * A custom {@link HandlerInterceptor} registered via
 * {@link org.springframework.web.servlet.config.annotation.WebMvcConfigurer#addInterceptors}.
 * <p>
 * Blocks access to paths ending with {@code /blocked} by returning 400 with
 * body {@code "bridge-interceptor-blocked"}, verifying that the interceptor bridge
 * is operational.
 */
public class CustomE2eInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CustomE2eInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        log.info("CustomE2eInterceptor.preHandle called for {}", uri);
        if (uri.endsWith("/blocked")) {
            log.info("CustomE2eInterceptor blocking request for {}", uri);
            response.setStatus(400);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("bridge-interceptor-blocked");
            response.getWriter().flush();
            return false;
        }
        return true;
    }
}

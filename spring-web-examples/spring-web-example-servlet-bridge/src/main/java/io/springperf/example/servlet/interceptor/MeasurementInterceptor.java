package io.springperf.example.servlet.interceptor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Slf4j
public class MeasurementInterceptor implements HandlerInterceptor {

    private static final String ATTR_START = "_mvc_start";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(ATTR_START, System.currentTimeMillis());
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           ModelAndView modelAndView) {
        Long start = (Long) request.getAttribute(ATTR_START);
        if (start != null) {
            log.info("[MVC-INTERCEPTOR] {} took {}ms", request.getRequestURI(), System.currentTimeMillis() - start);
        }
    }
}
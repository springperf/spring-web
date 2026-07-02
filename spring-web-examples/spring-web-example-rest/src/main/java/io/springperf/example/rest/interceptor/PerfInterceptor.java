package io.springperf.example.rest.interceptor;

import io.springperf.web.core.interceptor.HandlerInterceptor;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PerfInterceptor implements HandlerInterceptor {

    private static final String ATTR_START = "_perf_start";

    @Override
    public boolean preHandle(WebServerHttpRequest request, WebServerHttpResponse response, Object handler) throws Exception {
        request.getRequestContext().setAttribute(ATTR_START, System.currentTimeMillis());
        return true;
    }

    @Override
    public void postHandle(WebServerHttpRequest request, WebServerHttpResponse response, Object handler, Object result) throws Exception {
        Long start = (Long) request.getRequestContext().getAttribute(ATTR_START);
        if (start != null) {
            long elapsed = System.currentTimeMillis() - start;
            log.info("[INTERCEPTOR] handler={} took {}ms", handler, elapsed);
        }
    }
}
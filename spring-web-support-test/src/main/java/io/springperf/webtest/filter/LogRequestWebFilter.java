package io.springperf.webtest.filter;

import io.springperf.web.filter.FilterChain;
import io.springperf.web.filter.WebFilter;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class LogRequestWebFilter implements WebFilter {
    @Override
    public void doFilter(WebServerHttpRequest request, WebServerHttpResponse response, FilterChain chain) throws Exception {
        long startTime = System.currentTimeMillis();
        try {
            chain.doFilter(request, response);
        } finally {
            long costTimes = System.currentTimeMillis() - startTime;
            log.info("{} [{}] --- response:{}, cost: {}ms", request.getMethod(), request.getUriStr(), response.getStatus(), costTimes);
        }
    }
}

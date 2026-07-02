package io.springperf.example.servlet.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

@Slf4j
@Component
public class RequestLogFilter implements Filter {

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) servletRequest;
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(servletRequest, servletResponse);
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            log.info("[SERVLET-FILTER] {} {} -> {}ms",
                    req.getMethod(), req.getRequestURI(), elapsed);
        }
    }
}
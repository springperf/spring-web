package io.springperf.webtest.filter;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;

public class HealthFilter implements jakarta.servlet.Filter {

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, jakarta.servlet.FilterChain filterChain) throws IOException, ServletException {
        HttpServletResponse httpServletResponse = (HttpServletResponse) servletResponse;
        PrintWriter out = httpServletResponse.getWriter();
        out.println("{\"success\":true,\"message\":\"OK\"}");
        out.flush();
        httpServletResponse.flushBuffer();
        out.close();
    }
}

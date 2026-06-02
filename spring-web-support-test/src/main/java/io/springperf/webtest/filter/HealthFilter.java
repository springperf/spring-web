package io.springperf.webtest.filter;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

public class HealthFilter implements javax.servlet.Filter {

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, javax.servlet.FilterChain filterChain) throws IOException, ServletException {
        HttpServletResponse httpServletResponse = (HttpServletResponse) servletResponse;
        PrintWriter out = httpServletResponse.getWriter();
        out.println("{\"success\":true,\"message\":\"OK\"}");
        out.flush();
        httpServletResponse.flushBuffer();
        out.close();
    }
}

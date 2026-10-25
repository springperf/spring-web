package io.springperf.webtest.dynamic;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 动态注册的 filter：响应头值来自 {@code FilterRegistration#setInitParameter}。
 * <p>
 * 只有 {@code filter.init()} 真的被调用，init-param 才能读到；header 为空即证明初始化链路断了。
 * </p>
 */
public class DynHeaderFilter implements Filter {

    private String headerValue;

    @Override
    public void init(FilterConfig filterConfig) {
        this.headerValue = filterConfig.getInitParameter("headerValue");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        ((HttpServletResponse) response).setHeader("X-Dyn-Filter", headerValue);
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
    }
}

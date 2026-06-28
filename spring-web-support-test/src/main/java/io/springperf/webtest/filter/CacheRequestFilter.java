package io.springperf.webtest.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Request 缓存 Filter，实现它的可重复读取
 *
 * @author jy
 * @since 2023-12-06 16:35
 */
@Component
public class CacheRequestFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws IOException, ServletException {
        filterChain.doFilter(new CacheHttpServletRequestWrapper(request), response);
    }

}

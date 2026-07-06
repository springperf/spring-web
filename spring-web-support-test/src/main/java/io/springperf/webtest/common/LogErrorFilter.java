package io.springperf.webtest.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class LogErrorFilter extends OncePerRequestFilter {

    private final Logger log = LoggerFactory.getLogger(LogErrorFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) {
        long start = System.currentTimeMillis();
        String requestURI = request.getRequestURI();
        try {
            filterChain.doFilter(request, response);
        } catch (Throwable ex) {
            if (response.isCommitted()) {
                log.error("Response committed, cannot handle exception", ex);
                return;
            }
            if (isClientAbort(ex)) {
                log.warn("Client aborted connection: {}", ex.getClass().getName());
                return;
            }
            log.error("LogErrorFilter error:", ex);
            safelyReset(response);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json;charset=UTF-8");

            String body = buildErrorBody(request, ex);
            try {
                response.getWriter().write(body);
                response.getWriter().flush();
            } catch (IOException writeEx) {
                log.error("Failed to write error response", writeEx);
            }
        } finally {
            log.info("requestURI:{},  cost:{}ms", requestURI , System.currentTimeMillis() - start);
        }
    }

    private String buildErrorBody(HttpServletRequest request, Throwable ex) {
        return "{"
                + "\"code\":500,"
                + "\"message\":\"Internal Server Error\","
                + "\"path\":\"" + request.getRequestURI() + "\""
                + "}";
    }

    private void safelyReset(HttpServletResponse response) {
        try {
            response.reset();
        } catch (IllegalStateException ignore) {
            log.debug("response reset failed", ignore);
        }
    }

    private boolean isClientAbort(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            String name = t.getClass().getName();
            if ("org.apache.catalina.connector.ClientAbortException".equals(name)
                    || t instanceof java.io.EOFException || t instanceof java.net.SocketException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}

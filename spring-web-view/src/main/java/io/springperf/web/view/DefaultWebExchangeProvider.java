package io.springperf.web.view;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * 默认 {@link WebExchangeProvider}：纯框架实现，无 session / principal / ServletContext。
 *
 * <p>order 为 {@link org.springframework.core.Ordered#LOWEST_PRECEDENCE}，
 * {@link #supports} 恒返回 {@code true}，作为兜底保证任选机制总有可用实现。
 * 引入 Servlet 桥接层后，环境特定的 provider 会以更小的 order 优先命中。</p>
 *
 * @since 2.7.7
 */
public class DefaultWebExchangeProvider implements WebExchangeProvider {

    /**
     * 原生场景的空实现：四个 web 方法恒返回 {@code null}。
     *
     * <p>无状态且线程安全，故复用同一实例，避免每次渲染分配。</p>
     */
    private static final WebExchange EMPTY_EXCHANGE = new WebExchange() {
        @Override
        public HttpServletRequest getRequest() {
            return null;
        }

        @Override
        public HttpServletResponse getResponse() {
            return null;
        }

        @Override
        public HttpSession getSession() {
            return null;
        }

        @Override
        public ServletContext getServletContext() {
            return null;
        }
    };

    @Override
    public boolean supports(WebServerHttpRequest request) {
        return true;
    }

    @Override
    public WebExchange createExchange(WebServerHttpRequest request, WebServerHttpResponse response) {
        return EMPTY_EXCHANGE;
    }
}

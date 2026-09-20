package io.springperf.web.view;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.exchange.PerfWebExchange;
import org.thymeleaf.web.IWebExchange;

/**
 * 默认 {@link WebExchangeProvider}：纯框架实现，无 session / principal。
 *
 * <p>order 为 {@link org.springframework.core.Ordered#LOWEST_PRECEDENCE}，
 * {@link #supports} 恒返回 {@code true}，作为兜底保证任选机制总有可用实现。
 * 引入 Servlet 桥接层后，环境特定的 provider 会以更小的 order 优先命中。</p>
 *
 * @since 3.5.7
 */
public class DefaultWebExchangeProvider implements WebExchangeProvider {

    @Override
    public boolean supports(WebServerHttpRequest request) {
        return true;
    }

    @Override
    public IWebExchange createExchange(WebServerHttpRequest request, WebServerHttpResponse response) {
        return new PerfWebExchange(request, response);
    }
}

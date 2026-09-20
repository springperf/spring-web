package io.springperf.web.support.view;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import io.springperf.web.view.WebExchangeProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.thymeleaf.web.IWebExchange;

/**
 * Servlet 场景的 {@link WebExchangeProvider}：为模板引擎提供真实 session / principal / cookie。
 *
 * <p>仅当引入 {@code spring-web-servlet} 时注册（见 {@code SpringWebServletAutoConfiguration}）。
 * order 为 {@link Ordered#HIGHEST_PRECEDENCE}，优先于 view 模块的
 * {@code DefaultWebExchangeProvider}（{@link Ordered#LOWEST_PRECEDENCE}）。</p>
 *
 * <p>{@link ServletAttribute#getAdapterContext} 为懒建幂等操作：若当前请求尚未建立
 * Servlet 适配上下文则此处创建并缓存，后续 Servlet/Filter/JSP 路径复用同一实例。</p>
 *
 * @since 3.5.7
 */
public class ServletWebExchangeProvider implements WebExchangeProvider {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public boolean supports(WebServerHttpRequest request) {
        // servlet 模块存在即代表当前应用应使用 Servlet 语义；请求上下文不可用时降级为不支持，
        // 交由 DefaultWebExchangeProvider 兜底。
        return request != null && request.getRequestContext() != null;
    }

    @Override
    public IWebExchange createExchange(WebServerHttpRequest request, WebServerHttpResponse response) {
        ServletAdapterContext adapterContext = ServletAttribute.getAdapterContext(request, response);
        HttpServletRequest servletRequest = adapterContext.getRequest();
        return new ServletWebExchange(request, response, servletRequest);
    }
}

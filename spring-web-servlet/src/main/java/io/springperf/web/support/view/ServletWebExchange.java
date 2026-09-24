package io.springperf.web.support.view;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.exchange.PerfWebExchange;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.thymeleaf.web.IWebRequest;
import org.thymeleaf.web.IWebSession;

import java.security.Principal;

/**
 * {@link org.thymeleaf.web.IWebExchange} 的 Servlet 适配。
 * <p>
 * 在 {@link PerfWebExchange} 基础上补齐 session / principal / cookie：
 * </p>
 * <ul>
 * <li>{@code session} — {@code HttpServletRequest.getSession(false)} 的桥接； 请求未携带会话时返回
 * {@code null}（不主动创建，避免为纯渲染请求产生空会话）；</li>
 * <li>{@code principal} — {@code HttpServletRequest.getUserPrincipal()}；</li>
 * <li>{@code cookie} — 见 {@link ServletWebRequest}。</li>
 * </ul>
 *
 * @since 3.5.7
 */
public class ServletWebExchange extends PerfWebExchange {

    private final HttpServletRequest servletRequest;
    private final IWebRequest servletRequestAdapter;

    public ServletWebExchange(WebServerHttpRequest req, WebServerHttpResponse resp, HttpServletRequest servletRequest) {
        super(req, resp);
        this.servletRequest = servletRequest;
        this.servletRequestAdapter = new ServletWebRequest(req, this.contextPath, servletRequest);
    }

    @Override
    public IWebRequest getRequest() {
        return servletRequestAdapter;
    }

    @Override
    public IWebSession getSession() {
        HttpSession session = servletRequest.getSession(false);
        return session != null ? new ServletWebSession(session) : null;
    }

    @Override
    public Principal getPrincipal() {
        return servletRequest.getUserPrincipal();
    }
}

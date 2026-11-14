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

    /**
     * 上一次包装的底层会话与其适配器。模板里 {@code ${session.xxx}} 会被读取多次， 每次 new 一个 {@link ServletWebSession} 是纯重复分配；这里按底层会话引用复用包装。
     * <p>
     * 不能简单缓存 null：请求处理中途可能 {@code getSession(true)} 建出会话，因此每次都先 {@code getSession(false)} 取当前会话，仅在引用变化时重建包装。
     * </p>
     * <p>
     * 字段不加 volatile：与 {@code ServletWebRequest.cookieCache} 同一前提 —— exchange 由 {@code createExchange}
     * 按请求创建，只在该请求的渲染线程内使用。
     * </p>
     */
    private HttpSession cachedSession;
    private IWebSession cachedWebSession;

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
        if (session == null) {
            cachedSession = null;
            cachedWebSession = null;
            return null;
        }
        if (session != cachedSession) {
            cachedSession = session;
            cachedWebSession = new ServletWebSession(session);
        }
        return cachedWebSession;
    }

    @Override
    public Principal getPrincipal() {
        return servletRequest.getUserPrincipal();
    }
}

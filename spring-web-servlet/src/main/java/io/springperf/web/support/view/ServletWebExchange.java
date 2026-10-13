package io.springperf.web.support.view;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.WebExchange;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * Servlet 场景的 {@link WebExchange}：把 Servlet 容器对象暴露给模板渲染。
 *
 * <p>由 {@link ServletWebExchangeProvider} 基于请求上的 {@code ServletAdapterContext}
 * （{@code ServletAttribute.getAdapterContext} 懒建幂等）构造，因此同一请求上的
 * Servlet/Filter/JSP 路径与视图渲染共享同一组 servlet 对象。</p>
 *
 * @since 2.7.7
 */
public class ServletWebExchange implements WebExchange {

    private final WebServerHttpRequest nativeRequest;
    private final WebServerHttpResponse nativeResponse;
    private final HttpServletRequest servletRequest;
    private final HttpServletResponse servletResponse;

    public ServletWebExchange(WebServerHttpRequest nativeRequest, WebServerHttpResponse nativeResponse,
                              HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        this.nativeRequest = nativeRequest;
        this.nativeResponse = nativeResponse;
        this.servletRequest = servletRequest;
        this.servletResponse = servletResponse;
    }

    @Override
    public HttpServletRequest getRequest() {
        return servletRequest;
    }

    @Override
    public HttpServletResponse getResponse() {
        return servletResponse;
    }

    /**
     * 当前会话（{@code getSession(false)} 语义）：不存在时不创建，返回 {@code null}。
     *
     * <p>模板渲染不得隐式创建会话——那会让匿名访问产生会话，破坏无状态部署。</p>
     */
    @Override
    public HttpSession getSession() {
        return servletRequest != null ? servletRequest.getSession(false) : null;
    }

    @Override
    public ServletContext getServletContext() {
        return servletRequest != null ? servletRequest.getServletContext() : null;
    }

    /** 框架侧原始请求（需要框架能力而非 Servlet 语义时使用）。 */
    public WebServerHttpRequest getNativeRequest() {
        return nativeRequest;
    }

    /** 框架侧原始响应。 */
    public WebServerHttpResponse getNativeResponse() {
        return nativeResponse;
    }
}

package io.springperf.web.support.servlet;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.SupportDispatcherHandler;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.IOException;

public class PerfRequestDispatcher implements RequestDispatcher {

    private final String path;
    /** 目标资源路径（不含查询串），用于 {@code INCLUDE_SERVLET_PATH}。 */
    private final String targetPath;
    /** {@code getRequestDispatcher(path)} 路径自带的查询串，无则为 {@code null}。 */
    private final String targetQueryString;

    public PerfRequestDispatcher(String path) {
        this.path = path;
        // 路径可以自带查询串（如 "/x?a=1"）：SupportDispatcherHandler 内的
        // ForwardWebServerHttpRequest 会自行拆分出纯路径，这里同步拆一次供 include 属性使用。
        int queryIdx = path == null ? -1 : path.indexOf('?');
        this.targetPath = queryIdx >= 0 ? path.substring(0, queryIdx) : path;
        this.targetQueryString = queryIdx >= 0 ? path.substring(queryIdx + 1) : null;
    }

    @Override
    public void forward(ServletRequest request, ServletResponse response) throws ServletException, IOException {
        WebServerHttpRequest webRequest = resolveWebRequest(request);
        WebServerHttpResponse webResponse = resolveWebResponse(response);
        WebContext webContext = webRequest.getWebContext();
        RequestContext requestContext = webRequest.getRequestContext();

        if (webResponse.isCommitted()) {
            throw new IllegalStateException("Cannot forward: response already committed");
        }

        // Save forward attributes in request context（捕获「forward 前」的原始请求路径组件，符合 Servlet 规范）
        requestContext.setAttribute(RequestDispatcher.FORWARD_REQUEST_URI, webRequest.getUriStr());
        requestContext.setAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH, webContext.getContextPath());
        // 修正 2-20：原 SERVLET_PATH/PATH_INFO 颠倒。forward 属性应记录原始 servletPath/pathInfo；
        // 本框架 getServletPath()=getPath()，pathInfo 恒为 ""。
        requestContext.setAttribute(RequestDispatcher.FORWARD_SERVLET_PATH, webRequest.getPath());
        requestContext.setAttribute(RequestDispatcher.FORWARD_PATH_INFO, "");
        // 修正 2-20：FORWARD_QUERY_STRING 记录「原始请求」的纯查询串，不含 path、不含前导 '?'
        requestContext.setAttribute(RequestDispatcher.FORWARD_QUERY_STRING, queryStringOf(webRequest));

        // Set dispatcher type to FORWARD
        if (request instanceof PerfHttpServletRequest) {
            ((PerfHttpServletRequest) request).setDispatcherType(DispatcherType.FORWARD);
        }

        // 修正 2-19：forward 仅应重置 buffer（丢弃未提交 body），【不应】清空已写响应头、【不应】强制状态 200。
        // 原实现 getHeaders().clear() + setStatusCode(OK) 违反规范：转发链路会丢失已写入的响应头并被强制 200。
        webResponse.resetBuffer();

        DispatcherHandler dispatcher = webContext.getDispatcherHandler();
        if (!(dispatcher instanceof SupportDispatcherHandler)) {
            throw new ServletException("DispatcherHandler is not a SupportDispatcherHandler");
        }
        ((SupportDispatcherHandler) dispatcher).forward(webRequest, webResponse, path);
    }

    @Override
    public void include(ServletRequest request, ServletResponse response) throws ServletException, IOException {
        WebServerHttpRequest webRequest = resolveWebRequest(request);
        WebServerHttpResponse webResponse = resolveWebResponse(response);
        WebContext webContext = webRequest.getWebContext();
        RequestContext requestContext = webRequest.getRequestContext();

        // Save and set dispatcher type to INCLUDE
        DispatcherType originalDispatcherType = null;
        if (request instanceof PerfHttpServletRequest) {
            originalDispatcherType = ((PerfHttpServletRequest) request).getDispatcherType();
            ((PerfHttpServletRequest) request).setDispatcherType(DispatcherType.INCLUDE);
        }

        // INCLUDE_* 描述「被包含的目标资源」（Servlet 规范 / RequestDispatcher javadoc）。
        // QUERY_STRING 只写查询串本身，不含 path、不含前导 '?'；目标路径自带查询串时优先用它，
        // 否则沿用原始请求的查询串（ForwardWebServerHttpRequest 也会把原始 query 带到目标）。
        // 无查询串时写 null：RequestContext.setAttribute(name, null) 等价于移除属性。
        String includeQueryString = targetQueryString != null ? targetQueryString : queryStringOf(webRequest);
        requestContext.setAttribute(RequestDispatcher.INCLUDE_REQUEST_URI, webRequest.getUriStr());
        requestContext.setAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH, webContext.getContextPath());
        requestContext.setAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH, targetPath);
        requestContext.setAttribute(RequestDispatcher.INCLUDE_PATH_INFO, "");
        requestContext.setAttribute(RequestDispatcher.INCLUDE_QUERY_STRING, includeQueryString);

        DispatcherHandler dispatcher = webContext.getDispatcherHandler();
        if (!(dispatcher instanceof SupportDispatcherHandler)) {
            throw new ServletException("DispatcherHandler is not a SupportDispatcherHandler");
        }
        try {
            ((SupportDispatcherHandler) dispatcher).include(webRequest, webResponse, path);
        } finally {
            if (request instanceof PerfHttpServletRequest && originalDispatcherType != null) {
                ((PerfHttpServletRequest) request).setDispatcherType(originalDispatcherType);
            }
        }
    }

    /**
     * 取请求 URI 中的纯查询串（不含 path、不含前导 {@code '?'}）；无查询串时返回 {@code null}。
     * 只切第一个 {@code '?'}，后续内容（含 {@code '%'} 编码与其它 {@code '?'}）原样保留，不解码。
     */
    private static String queryStringOf(WebServerHttpRequest webRequest) {
        String uriWithQuery = webRequest.getUriStrWithQuery();
        int queryIdx = uriWithQuery == null ? -1 : uriWithQuery.indexOf('?');
        return queryIdx >= 0 ? uriWithQuery.substring(queryIdx + 1) : null;
    }

    private static WebServerHttpRequest resolveWebRequest(ServletRequest request) throws ServletException {
        if (request instanceof PerfHttpServletRequest) {
            return ((PerfHttpServletRequest) request).getDelegateRequest();
        }
        ServletRequest unwrapped = request;
        while (unwrapped instanceof HttpServletRequestWrapper) {
            ServletRequest next = ((HttpServletRequestWrapper) unwrapped).getRequest();
            if (next == unwrapped)
                break;
            unwrapped = next;
            if (unwrapped instanceof PerfHttpServletRequest) {
                return ((PerfHttpServletRequest) unwrapped).getDelegateRequest();
            }
        }
        ServletAdapterContext ctx = findAdapterContext(unwrapped);
        if (ctx != null) {
            return ctx.getPerfRequest().getDelegateRequest();
        }
        throw new ServletException("Cannot resolve WebServerHttpRequest from " + request.getClass());
    }

    private static WebServerHttpResponse resolveWebResponse(ServletResponse response) throws ServletException {
        if (response instanceof PerfHttpServletResponse) {
            return ((PerfHttpServletResponse) response).getResponse();
        }
        while (response instanceof HttpServletResponseWrapper) {
            ServletResponse wrapped = ((HttpServletResponseWrapper) response).getResponse();
            if (wrapped == response) {
                break;
            }
            response = wrapped;
            if (response instanceof PerfHttpServletResponse) {
                return ((PerfHttpServletResponse) response).getResponse();
            }
        }
        throw new ServletException("Cannot resolve WebServerHttpResponse from " + response.getClass());
    }

    private static ServletAdapterContext findAdapterContext(ServletRequest request) {
        if (request instanceof PerfHttpServletRequest) {
            WebServerHttpRequest webRequest = ((PerfHttpServletRequest) request).getDelegateRequest();
            return ServletAttribute.getAdapterContext(webRequest.getRequestContext());
        }
        return null;
    }
}

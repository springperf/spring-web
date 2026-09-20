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

    public PerfRequestDispatcher(String path) {
        this.path = path;
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
        // 修正 2-20：QUERY_STRING 不应含前导 '?'（规范要求纯查询串）
        String forwardUriWithQuery = webRequest.getUriStrWithQuery();
        int forwardQIdx = forwardUriWithQuery.indexOf('?');
        requestContext.setAttribute(RequestDispatcher.FORWARD_QUERY_STRING,
                forwardQIdx >= 0 ? forwardUriWithQuery.substring(forwardQIdx + 1) : null);

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

        // Save include attributes in request context
        requestContext.setAttribute(RequestDispatcher.INCLUDE_REQUEST_URI, webRequest.getUriStr());
        requestContext.setAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH, webContext.getContextPath());
        requestContext.setAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH, path);
        requestContext.setAttribute(RequestDispatcher.INCLUDE_PATH_INFO, "");
        requestContext.setAttribute(RequestDispatcher.INCLUDE_QUERY_STRING, webRequest.getUriStrWithQuery());

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

    private static WebServerHttpRequest resolveWebRequest(ServletRequest request) throws ServletException {
        if (request instanceof PerfHttpServletRequest) {
            return ((PerfHttpServletRequest) request).getDelegateRequest();
        }
        ServletRequest unwrapped = request;
        while (unwrapped instanceof HttpServletRequestWrapper) {
            ServletRequest next = ((HttpServletRequestWrapper) unwrapped).getRequest();
            if (next == unwrapped) break;
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
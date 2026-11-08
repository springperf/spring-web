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
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.servlet.http.MappingMatch;

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
        // FORWARD_MAPPING 描述「原始请求」的映射（Servlet 4.0+，值为 HttpServletMapping 而非 String）；
        // 本框架 getServletPath() = getPath()、按路径精确派发，故用原始请求路径构造 EXACT 映射。
        requestContext.setAttribute(RequestDispatcher.FORWARD_MAPPING, pathMapping(webRequest.getPath()));

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

        // 校验前移：原实现在写完 INCLUDE_* 属性之后才校验 dispatcher，校验抛异常时属性已写入且无人清理
        DispatcherHandler dispatcher = webContext.getDispatcherHandler();
        if (!(dispatcher instanceof SupportDispatcherHandler)) {
            throw new ServletException("DispatcherHandler is not a SupportDispatcherHandler");
        }

        // 保存 include 前的 INCLUDE_* 旧值：嵌套 include 结束后必须恢复「外层」的值，而不是一律移除
        String oldRequestUri = (String) requestContext.getAttribute(RequestDispatcher.INCLUDE_REQUEST_URI);
        String oldContextPath = (String) requestContext.getAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH);
        String oldServletPath = (String) requestContext.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH);
        String oldPathInfo = (String) requestContext.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO);
        String oldQueryString = (String) requestContext.getAttribute(RequestDispatcher.INCLUDE_QUERY_STRING);
        Object oldMapping = requestContext.getAttribute(RequestDispatcher.INCLUDE_MAPPING);

        DispatcherType originalDispatcherType = null;
        if (request instanceof PerfHttpServletRequest) {
            originalDispatcherType = ((PerfHttpServletRequest) request).getDispatcherType();
            ((PerfHttpServletRequest) request).setDispatcherType(DispatcherType.INCLUDE);
        }
        try {
            setIncludeAttributes(requestContext, webRequest, webContext.getContextPath());
            ((SupportDispatcherHandler) dispatcher).include(webRequest, webResponse, path);
        } finally {
            // 回填旧值：null 等价于移除（RequestContext.setAttribute(name, null)）
            requestContext.setAttribute(RequestDispatcher.INCLUDE_REQUEST_URI, oldRequestUri);
            requestContext.setAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH, oldContextPath);
            requestContext.setAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH, oldServletPath);
            requestContext.setAttribute(RequestDispatcher.INCLUDE_PATH_INFO, oldPathInfo);
            requestContext.setAttribute(RequestDispatcher.INCLUDE_QUERY_STRING, oldQueryString);
            requestContext.setAttribute(RequestDispatcher.INCLUDE_MAPPING, oldMapping);
            if (originalDispatcherType != null) {
                ((PerfHttpServletRequest) request).setDispatcherType(originalDispatcherType);
            }
        }
    }

    /**
     * 写入 {@code INCLUDE_*} 属性。这些属性描述「被包含的目标资源」（Servlet 规范 / {@code RequestDispatcher} javadoc）， 而非原始请求：
     * <ul>
     * <li>{@code INCLUDE_REQUEST_URI} = contextPath + 目标路径（原实现误写原始请求 URI，与 SERVLET_PATH 不自洽）；</li>
     * <li>{@code INCLUDE_SERVLET_PATH} = 目标路径，{@code INCLUDE_PATH_INFO} = ""（本框架 {@code getServletPath() = getPath()}、
     * pathInfo 恒为空串，见 {@code PerfHttpServletRequest#getPathInfo()}）；</li>
     * <li>{@code INCLUDE_QUERY_STRING} 只写查询串本身，不含 path、不含前导 {@code '?'}；目标路径自带查询串时优先用它，
     * 否则沿用原始请求的查询串（{@code ForwardWebServerHttpRequest} 也会把原始 query 带到目标）；无查询串时写 null
     * （{@code RequestContext.setAttribute(name, null)} 等价于移除属性）；</li>
     * <li>{@code INCLUDE_MAPPING}（Servlet 4.0+）值为 {@link HttpServletMapping}，按目标路径构造精确映射。</li>
     * </ul>
     */
    private void setIncludeAttributes(RequestContext requestContext, WebServerHttpRequest webRequest,
            String contextPath) {
        requestContext.setAttribute(RequestDispatcher.INCLUDE_REQUEST_URI, includeRequestUri(contextPath, targetPath));
        requestContext.setAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH, contextPath);
        requestContext.setAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH, targetPath);
        requestContext.setAttribute(RequestDispatcher.INCLUDE_PATH_INFO, "");
        requestContext.setAttribute(RequestDispatcher.INCLUDE_QUERY_STRING,
                targetQueryString != null ? targetQueryString : queryStringOf(webRequest));
        requestContext.setAttribute(RequestDispatcher.INCLUDE_MAPPING, pathMapping(targetPath));
    }

    /** 被包含资源的 request URI：contextPath + 目标路径（目标路径已相对 context，见 {@code NettyHttpHandler} 的剥离逻辑）。 */
    private static String includeRequestUri(String contextPath, String targetPath) {
        if (targetPath == null || targetPath.isEmpty()) {
            return contextPath == null ? "" : contextPath;
        }
        if (contextPath == null || contextPath.isEmpty()) {
            return targetPath;
        }
        if (targetPath.startsWith("/")) {
            return contextPath + targetPath;
        }
        return contextPath + "/" + targetPath;
    }

    /**
     * 按路径构造精确映射。本框架没有 Servlet url-pattern，{@code getRequestDispatcher(path)} 等价于 path 派发， 因此 {@code pattern}
     * 即目标路径、{@code mappingMatch} 恒为 {@link MappingMatch#EXACT}。 {@code servletName} 返回空串：include/forward 目标由 MVC
     * 路由处理，没有 Servlet 名称。
     */
    private static HttpServletMapping pathMapping(String path) {
        return new ExactPathHttpServletMapping(path);
    }

    /** 不可变、线程安全的 {@link HttpServletMapping} 实现（EXACT 语义见该接口 javadoc 的示例表格）。 */
    private static final class ExactPathHttpServletMapping implements HttpServletMapping {

        /** url-pattern，保留前导 {@code '/'}。 */
        private final String pattern;
        /** EXACT 语义要求匹配值**省略**前导 {@code '/'}；根路径为空串。 */
        private final String matchValue;

        ExactPathHttpServletMapping(String path) {
            String normalized = path == null ? "" : path;
            this.pattern = normalized;
            this.matchValue = normalized.startsWith("/") ? normalized.substring(1) : normalized;
        }

        @Override
        public String getMatchValue() {
            return matchValue;
        }

        @Override
        public String getPattern() {
            return pattern;
        }

        @Override
        public String getServletName() {
            return "";
        }

        @Override
        public MappingMatch getMappingMatch() {
            return MappingMatch.EXACT;
        }
    }

    /**
     * 取请求 URI 中的纯查询串（不含 path、不含前导 {@code '?'}）；无查询串时返回 {@code null}。 只切第一个 {@code '?'}，后续内容（含 {@code '%'} 编码与其它
     * {@code '?'}）原样保留，不解码。
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

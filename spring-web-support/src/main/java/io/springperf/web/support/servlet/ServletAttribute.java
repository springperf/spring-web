package io.springperf.web.support.servlet;

import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.context.ServletAdapterContext;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 类型安全的 Servlet 适配对象访问器。
 *
 * <p>通过 {@link RequestAttribute} 在 RequestContext 的 fastAttributes 数组
 * 中存取 {@link ServletAdapterContext}，避免 ConcurrentHashMap 查找和
 * ThreadLocal 操作。</p>
 */
public final class ServletAttribute {

    private static final RequestAttribute<ServletAdapterContext> ADAPTER_CTX =
            RequestAttribute.createAttribute(ServletAdapterContext.class);

    private ServletAttribute() {}

    /**
     * Exposed for testing - returns the {@link RequestAttribute} key
     * used for storing the {@link ServletAdapterContext}.
     */
    public static RequestAttribute<ServletAdapterContext> getAttributeKey() {
        return ADAPTER_CTX;
    }

    public static ServletAdapterContext getAdapterContext(RequestContext ctx) {
        return ctx.getAttribute(ADAPTER_CTX);
    }

    public static void setAdapterContext(RequestContext ctx, ServletAdapterContext adapterContext) {
        ctx.setAttribute(ADAPTER_CTX, adapterContext);
    }

    /**
     * 获取当前请求的 {@link ServletAdapterContext}，如果不存在则创建并存入。
     * 如果已存在，检测 WebFilter 是否包装了请求并更新委托引用。
     * <p>项目可能没有 {@code FilterWrapper}（无 javax.servlet.Filter），
     * 此方法确保始终能拿到有效的 adapter context。</p>
     */
    public static ServletAdapterContext getAdapterContext(WebServerHttpRequest request, WebServerHttpResponse response) {
        RequestContext ctx = request.getRequestContext();
        ServletAdapterContext adapterContext = getAdapterContext(ctx);
        if (adapterContext == null) {
            adapterContext = new ServletAdapterContext(new PerfHttpServletRequest(request), new PerfHttpServletResponse(response), null);
            setAdapterContext(ctx, adapterContext);
        }
        adapterContext.rebindFrameworkRequest(request);
        adapterContext.rebindFrameworkResponse(response);
        return adapterContext;
    }

    public static HttpServletRequest getRequest(RequestContext ctx) {
        ServletAdapterContext adapter = ctx.getAttribute(ADAPTER_CTX);
        return adapter != null ? adapter.getRequest() : null;
    }

    public static HttpServletResponse getResponse(RequestContext ctx) {
        ServletAdapterContext adapter = ctx.getAttribute(ADAPTER_CTX);
        return adapter != null ? adapter.getResponse() : null;
    }
}
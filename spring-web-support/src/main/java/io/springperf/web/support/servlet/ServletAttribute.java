package io.springperf.web.support.servlet;

import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
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

    public static HttpServletRequest getRequest(RequestContext ctx) {
        ServletAdapterContext adapter = ctx.getAttribute(ADAPTER_CTX);
        return adapter != null ? adapter.getRequest() : null;
    }

    public static HttpServletResponse getResponse(RequestContext ctx) {
        ServletAdapterContext adapter = ctx.getAttribute(ADAPTER_CTX);
        return adapter != null ? adapter.getResponse() : null;
    }
}
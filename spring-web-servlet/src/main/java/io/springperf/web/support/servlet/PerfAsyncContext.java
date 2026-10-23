package io.springperf.web.support.servlet;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.SupportDispatcherHandler;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PerfAsyncContext implements AsyncContext {

    private static final RequestAttribute<PerfAsyncContext> ASYNC_CONTEXT_ATTR = RequestAttribute
            .createAttribute(PerfAsyncContext.class);

    private static final ExecutorService TASK_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "async-context-worker");
        t.setDaemon(true);
        return t;
    });

    private final PerfAsyncWebRequest asyncWebRequest;
    private final WebServerHttpRequest webRequest;
    private final WebServerHttpResponse webResponse;
    private final ServletRequest servletRequest;
    private final ServletResponse servletResponse;
    /** startAsync 时传入的是否就是路由分发用的原始 request/response（决定 hasOriginalRequestAndResponse）。 */
    private final boolean originalRequestResponse;
    private final List<Registration> listeners = new CopyOnWriteArrayList<>();
    private volatile long timeout = 30000L;
    private volatile boolean handlersRegistered;
    /**
     * 当前异步周期是否已结束（complete 或 dispatch）。 Servlet 规范：{@code isAsyncStarted()} 在异步已 dispatch/complete 后必须返回
     * false；请求属性本身不清理，故状态必须单独记。
     */
    private volatile boolean completed;

    public PerfAsyncContext(PerfAsyncWebRequest asyncWebRequest, WebServerHttpRequest webRequest,
            WebServerHttpResponse webResponse, ServletRequest servletRequest, ServletResponse servletResponse,
            boolean originalRequestResponse) {
        this.asyncWebRequest = asyncWebRequest;
        this.webRequest = webRequest;
        this.webResponse = webResponse;
        this.servletRequest = servletRequest;
        this.servletResponse = servletResponse;
        this.originalRequestResponse = originalRequestResponse;
    }

    /**
     * 懒注册底层框架的 timeout/error 回调到 AsyncListener，仅在需要时（添加监听器或设置超时）注册， 避免无条件覆盖框架（如 Callable/DeferredResult 路径）已有的 handler。
     */
    private void ensureHandlersRegistered() {
        if (handlersRegistered) {
            return;
        }
        handlersRegistered = true;
        asyncWebRequest.addTimeoutHandler(this::fireOnTimeout);
        asyncWebRequest.addErrorHandler(this::fireOnError);
    }

    public static void set(RequestContext ctx, PerfAsyncContext asyncContext) {
        ctx.setAttribute(ASYNC_CONTEXT_ATTR, asyncContext);
    }

    public static PerfAsyncContext get(RequestContext ctx) {
        return ctx.getAttribute(ASYNC_CONTEXT_ATTR);
    }

    @Override
    public ServletRequest getRequest() {
        return servletRequest;
    }

    @Override
    public ServletResponse getResponse() {
        return servletResponse;
    }

    @Override
    public boolean hasOriginalRequestAndResponse() {
        return originalRequestResponse;
    }

    /** 异步周期是否已结束（complete / dispatch）；供 {@code HttpServletRequest#isAsyncStarted()} 判定。 */
    public boolean isCompleted() {
        return completed;
    }

    @Override
    public void dispatch() {
        asyncWebRequest.dispatch();
        endAsyncCycle();
    }

    @Override
    public void dispatch(String path) {
        WebContext webContext = webRequest.getWebContext();
        DispatcherHandler dispatcher = webContext.getDispatcherHandler();
        if (dispatcher instanceof SupportDispatcherHandler) {
            DispatcherType original = switchDispatcherTypeToAsync();
            try {
                ((SupportDispatcherHandler) dispatcher).forward(webRequest, webResponse, path);
            } finally {
                restoreDispatcherType(original);
            }
        } else {
            asyncWebRequest.dispatch();
        }
        endAsyncCycle();
    }

    @Override
    public void dispatch(ServletContext context, String path) {
        dispatch(path);
    }

    @Override
    public void complete() {
        endAsyncCycle();
        asyncWebRequest.complete();
    }

    /** 结束异步周期并向监听器发出 onComplete（规范：一个异步周期只完成一次）。 */
    private void endAsyncCycle() {
        if (completed) {
            return;
        }
        completed = true;
        fireOnComplete();
    }

    /**
     * 异步分派期间 Servlet 层看到的 DispatcherType 必须是 {@code ASYNC}。
     * <p>
     * 分派本身复用 {@link SupportDispatcherHandler#forward}（重新映射 + 直达目标 handler）， 若不显式切换，目标 handler 里
     * {@code request.getDispatcherType()} 会停留在 REQUEST/FORWARD， 依赖该值区分「首次请求 / 异步重放」的业务代码会走错分支。
     * </p>
     *
     * @return 原 DispatcherType（非 {@link PerfHttpServletRequest} 时返回 null）
     */
    private DispatcherType switchDispatcherTypeToAsync() {
        if (servletRequest instanceof PerfHttpServletRequest) {
            PerfHttpServletRequest perfRequest = (PerfHttpServletRequest) servletRequest;
            DispatcherType original = perfRequest.getDispatcherType();
            perfRequest.setDispatcherType(DispatcherType.ASYNC);
            return original;
        }
        return null;
    }

    private void restoreDispatcherType(DispatcherType original) {
        if (original != null && servletRequest instanceof PerfHttpServletRequest) {
            ((PerfHttpServletRequest) servletRequest).setDispatcherType(original);
        }
    }

    private void fireOnComplete() {
        AsyncEvent event = null;
        for (Registration registration : listeners) {
            if (event == null) {
                event = registration.toEvent(this);
            }
            try {
                registration.listener.onComplete(event);
            } catch (Exception ignored) {
            }
        }
    }

    private void fireOnTimeout() {
        AsyncEvent event = null;
        for (Registration registration : listeners) {
            if (event == null) {
                event = registration.toEvent(this);
            }
            try {
                registration.listener.onTimeout(event);
            } catch (Exception ignored) {
            }
        }
    }

    private void fireOnError(Throwable throwable) {
        if (listeners.isEmpty()) {
            return;
        }
        for (Registration registration : listeners) {
            AsyncEvent event = registration.toEvent(this, throwable);
            try {
                registration.listener.onError(event);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void start(Runnable run) {
        TASK_EXECUTOR.execute(run);
    }

    @Override
    public void addListener(AsyncListener listener) {
        listeners.add(new Registration(listener, servletRequest, servletResponse));
        ensureHandlersRegistered();
    }

    @Override
    public void addListener(AsyncListener listener, ServletRequest request, ServletResponse response) {
        // 规范：监听器收到的 AsyncEvent 必须携带注册时传入的 request/response（可能是业务包装过的）
        listeners.add(new Registration(listener, request, response));
        ensureHandlersRegistered();
    }

    @Override
    public <T extends AsyncListener> T createListener(Class<T> clazz) {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to create AsyncListener: " + clazz, e);
        }
    }

    @Override
    public void setTimeout(long timeout) {
        this.timeout = timeout;
        asyncWebRequest.setTimeout(timeout);
        ensureHandlersRegistered();
        asyncWebRequest.scheduleTimeoutIfNeeded();
    }

    @Override
    public long getTimeout() {
        return timeout;
    }

    /** 一个监听器注册项：首次注册时携带的 request/response 决定其 AsyncEvent 内容。 */
    private static final class Registration {

        private final AsyncListener listener;

        private final ServletRequest request;

        private final ServletResponse response;

        Registration(AsyncListener listener, ServletRequest request, ServletResponse response) {
            this.listener = listener;
            this.request = request;
            this.response = response;
        }

        AsyncEvent toEvent(AsyncContext context) {
            ServletRequest req = request != null ? request : context.getRequest();
            ServletResponse resp = response != null ? response : context.getResponse();
            return new AsyncEvent(context, req, resp);
        }

        AsyncEvent toEvent(AsyncContext context, Throwable throwable) {
            ServletRequest req = request != null ? request : context.getRequest();
            ServletResponse resp = response != null ? response : context.getResponse();
            return new AsyncEvent(context, req, resp, throwable);
        }
    }
}

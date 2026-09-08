package io.springperf.web.support.servlet;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.SupportDispatcherHandler;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletContext;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PerfAsyncContext implements AsyncContext {

    private static final RequestAttribute<PerfAsyncContext> ASYNC_CONTEXT_ATTR =
            RequestAttribute.createAttribute(PerfAsyncContext.class);

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
    private final List<AsyncListener> listeners = new CopyOnWriteArrayList<>();
    private volatile long timeout = 30000L;
    private volatile boolean handlersRegistered;

    public PerfAsyncContext(PerfAsyncWebRequest asyncWebRequest,
                            WebServerHttpRequest webRequest, WebServerHttpResponse webResponse,
                            ServletRequest servletRequest, ServletResponse servletResponse) {
        this.asyncWebRequest = asyncWebRequest;
        this.webRequest = webRequest;
        this.webResponse = webResponse;
        this.servletRequest = servletRequest;
        this.servletResponse = servletResponse;
    }

    /**
     * 懒注册底层框架的 timeout/error 回调到 AsyncListener，仅在需要时（添加监听器或设置超时）注册，
     * 避免无条件覆盖框架（如 Callable/DeferredResult 路径）已有的 handler。
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
        return true;
    }

    @Override
    public void dispatch() {
        asyncWebRequest.dispatch();
        fireOnComplete();
    }

    @Override
    public void dispatch(String path) {
        WebContext webContext = webRequest.getWebContext();
        DispatcherHandler dispatcher = webContext.getDispatcherHandler();
        if (dispatcher instanceof SupportDispatcherHandler) {
            ((SupportDispatcherHandler) dispatcher).forward(webRequest, webResponse, path);
        } else {
            asyncWebRequest.dispatch();
        }
        fireOnComplete();
    }

    @Override
    public void dispatch(ServletContext context, String path) {
        dispatch(path);
    }

    @Override
    public void complete() {
        fireOnComplete();
        asyncWebRequest.complete();
    }

    private void fireOnComplete() {
        if (listeners.isEmpty()) {
            return;
        }
        AsyncEvent event = new AsyncEvent(this, servletRequest, servletResponse);
        for (AsyncListener listener : listeners) {
            try {
                listener.onComplete(event);
            } catch (Exception ignored) {
            }
        }
    }

    private void fireOnTimeout() {
        if (listeners.isEmpty()) {
            return;
        }
        AsyncEvent event = new AsyncEvent(this, servletRequest, servletResponse);
        for (AsyncListener listener : listeners) {
            try {
                listener.onTimeout(event);
            } catch (Exception ignored) {
            }
        }
    }

    private void fireOnError(Throwable throwable) {
        if (listeners.isEmpty()) {
            return;
        }
        AsyncEvent event = throwable != null
                ? new AsyncEvent(this, servletRequest, servletResponse, throwable)
                : new AsyncEvent(this, servletRequest, servletResponse);
        for (AsyncListener listener : listeners) {
            try {
                listener.onError(event);
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
        listeners.add(listener);
        ensureHandlersRegistered();
    }

    @Override
    public void addListener(AsyncListener listener, ServletRequest request, ServletResponse response) {
        listeners.add(listener);
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
}
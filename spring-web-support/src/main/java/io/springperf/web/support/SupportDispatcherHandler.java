package io.springperf.web.support;

import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.http.WriteRespEventListener;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import io.springperf.web.support.servlet.session.PerfHttpSession;
import io.springperf.web.support.servlet.session.PerfHttpSessionManager;
import org.springframework.core.Ordered;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class SupportDispatcherHandler extends DispatcherHandler {

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 30000;
    }

    @Override
    protected boolean initContextHolders(WebServerHttpRequest req, WebServerHttpResponse resp) {
        boolean init = super.initContextHolders(req, resp);
        ServletRequestAttributes requestAttributes = buildRequestAttributes(req, resp);
        RequestContextHolder.setRequestAttributes(requestAttributes, this.threadContextInheritable);
        // 在响应写入完成后持久化 session，同步/异步请求均走此路径
        resp.addWriteRespEventListener(new SessionFlushListener(req));
        return init || requestAttributes != null;
    }

    @Override
    protected void removeContextHolders(WebServerHttpRequest req, WebServerHttpResponse resp) {
        super.removeContextHolders(req, resp);
        RequestContextHolder.resetRequestAttributes();
    }

    protected ServletRequestAttributes buildRequestAttributes(WebServerHttpRequest req, WebServerHttpResponse resp) {
        ServletAdapterContext adapterContext = ServletAttribute.getAdapterContext(req, resp);
        return new ServletRequestAttributes(adapterContext.getRequest(), adapterContext.getResponse());
    }

    @Override
    public String getComponentName() {
        return DispatcherHandler.class.getSimpleName();
    }

    /**
     * 在响应写入完成/失败时持久化 session。
     * 通过 {@link WriteRespEventListener} 接入 Netty 的 ChannelFuture 回调，
     * 确保在 同步/异步/流式 场景下均在正确的生命周期点执行。
     */
    private static class SessionFlushListener implements WriteRespEventListener {

        private final WebServerHttpRequest request;

        SessionFlushListener(WebServerHttpRequest request) {
            this.request = request;
        }

        @Override
        public void completeSuccessCallback() {
            flushSession();
        }

        @Override
        public void completeErrorCallback(Throwable throwable) {
            // 响应写入失败，仍更新 lastAccessedTime 防止 session 过早过期
            flushSession();
        }

        private void flushSession() {
            PerfHttpSession session = request.getRequestContext()
                    .getAttribute(PerfHttpSessionManager.SESSION_ATTR_KEY);
            if (session == null || session.isInvalid()) {
                return;
            }
            PerfHttpSessionManager manager = request.getWebContext()
                    .getWebComponent(PerfHttpSessionManager.class);
            if (manager != null) {
                session.markAccessed();
                manager.saveSession(session);
            }
        }
    }
}
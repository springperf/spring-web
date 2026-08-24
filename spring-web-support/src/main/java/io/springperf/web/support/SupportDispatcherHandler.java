package io.springperf.web.support;

import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.http.WebServerHttpResponseWrapper;
import io.springperf.web.http.WriteRespEventListener;
import org.springframework.http.HttpHeaders;
import io.springperf.web.support.servlet.ForwardWebServerHttpRequest;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import io.springperf.web.support.servlet.session.PerfHttpSession;
import io.springperf.web.support.servlet.session.PerfHttpSessionManager;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.io.IOException;
import java.io.OutputStream;

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
     * 将请求转发到指定路径，重新走映射和 dispatch（跳过 filter 链）。
     * 由 {@link jakarta.servlet.RequestDispatcher#forward} 调用。
     */
    public void forward(WebServerHttpRequest req, WebServerHttpResponse resp, String forwardPath) {
        ForwardWebServerHttpRequest wrappedReq = new ForwardWebServerHttpRequest(req, forwardPath);
        io.springperf.web.core.mapping.MappingResult newResult = mappingRegistry.mapping(wrappedReq);
        LocaleContext savedLocale = LocaleContextHolder.getLocaleContext();
        ServletRequestAttributes savedRequest = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        try {
            handleAfterFilter(wrappedReq, resp, newResult);
        } finally {
            if (savedLocale != null) {
                LocaleContextHolder.setLocaleContext(savedLocale, this.threadContextInheritable);
            } else {
                LocaleContextHolder.resetLocaleContext();
            }
            if (savedRequest != null) {
                RequestContextHolder.setRequestAttributes(savedRequest, this.threadContextInheritable);
            } else {
                RequestContextHolder.resetRequestAttributes();
            }
        }
    }

    /**
     * 将请求包含到指定路径，目标 handler 的输出追加到当前响应 body。
     * 由 {@link jakarta.servlet.RequestDispatcher#include} 调用。
     */
    public void include(WebServerHttpRequest req, WebServerHttpResponse resp, String includePath) {
        ForwardWebServerHttpRequest wrappedReq = new ForwardWebServerHttpRequest(req, includePath);
        io.springperf.web.core.mapping.MappingResult newResult = mappingRegistry.mapping(wrappedReq);
        IncludeResponseWrapper includeResp = new IncludeResponseWrapper(resp);
        LocaleContext savedLocale = LocaleContextHolder.getLocaleContext();
        ServletRequestAttributes savedRequest = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        try {
            handleAfterFilter(wrappedReq, includeResp, newResult);
        } finally {
            if (savedLocale != null) {
                LocaleContextHolder.setLocaleContext(savedLocale, this.threadContextInheritable);
            } else {
                LocaleContextHolder.resetLocaleContext();
            }
            if (savedRequest != null) {
                RequestContextHolder.setRequestAttributes(savedRequest, this.threadContextInheritable);
            } else {
                RequestContextHolder.resetRequestAttributes();
            }
        }
    }

    /**
     * 阻止 flush 的响应包装器，用于 {@link #include}，防止目标 handler 的 flush 刷新原始响应。
     */
    private static class IncludeResponseWrapper extends WebServerHttpResponseWrapper {

        private final HttpStatus originalStatus;

        IncludeResponseWrapper(WebServerHttpResponse response) {
            super(response);
            this.originalStatus = response.getStatus();
        }

        @Override
        public boolean isHandled() {
            return false;
        }

        @Override
        public void flush() throws IOException {
        }

        @Override
        public void flush(boolean chunked) throws IOException {
        }

        @Override
        public boolean setHandled() {
            return false;
        }

        @Override
        public void setStatusCode(HttpStatusCode statusCode) {
        }

        @Override
        public void sendError(HttpStatus statusCode) {
        }

        @Override
        public void sendError(HttpStatus statusCode, String message) {
        }

        @Override
        public HttpStatus getStatus() {
            return originalStatus;
        }

        @Override
        public HttpHeaders getHeaders() {
            return new HttpHeaders();
        }
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
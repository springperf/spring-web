package io.springperf.web.core;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.ArgumentResolverRegistry;
import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.core.async.AsyncSupportUtils;
import io.springperf.web.core.cors.CorsRegistry;
import io.springperf.web.core.cors.CorsUtils;
import io.springperf.web.core.exception.ExceptionRegistry;
import io.springperf.web.core.filter.WebFilterRegistry;
import io.springperf.web.core.interceptor.InterceptorRegistry;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.metrics.NoOpWebMetrics;
import io.springperf.web.core.metrics.WebMetrics;
import io.springperf.web.core.pool.BizPoolRegistry;
import io.springperf.web.core.retval.ReturnValueResolverRegistry;
import io.springperf.web.http.BaseWebServerHttpResponse;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.server.HttpHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Central dispatcher: route lookup, optional offload to business thread pool, argument resolution, handler method invocation, and return value processing.
 */
@Slf4j
public class DispatcherHandler extends BaseWebComponent implements HttpHandler {
    private static final ResponseStatusException NOT_FOUND_EXCEPTION = new ResponseStatusException(HttpStatus.NOT_FOUND);
    private static final ResponseStatusException METHOD_NOT_ALLOWED_EXCEPTION = new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED);

    protected boolean threadContextInheritable = false;

    protected MappingRegistry mappingRegistry;
    protected ExceptionRegistry exceptionRegistry;
    protected ArgumentResolverRegistry argumentResolverRegistry;
    protected ReturnValueResolverRegistry returnValueResolverRegistry;
    protected CorsRegistry corsRegistry;
    protected InterceptorRegistry interceptorRegistry;
    protected AsyncSupportRegistry asyncSupportRegistry;
    protected BizPoolRegistry bizPoolRegistry;
    protected WebFilterRegistry webFilterRegistry;
    protected WebMetrics metrics;

    private static final RequestAttribute<Long> METRICS_START_ATTR =
            RequestAttribute.createAttribute(Long.class);

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        this.mappingRegistry = webContext.getWebComponentWithDefault(MappingRegistry.class, new MappingRegistry());
        this.exceptionRegistry = webContext.getWebComponentWithDefault(ExceptionRegistry.class, new ExceptionRegistry());
        this.argumentResolverRegistry = webContext.getWebComponentWithDefault(ArgumentResolverRegistry.class, new ArgumentResolverRegistry());
        this.returnValueResolverRegistry = webContext.getWebComponentWithDefault(ReturnValueResolverRegistry.class, new ReturnValueResolverRegistry());
        this.corsRegistry = webContext.getWebComponentWithDefault(CorsRegistry.class, new CorsRegistry());
        this.interceptorRegistry = webContext.getWebComponentWithDefault(InterceptorRegistry.class, new InterceptorRegistry());
        this.asyncSupportRegistry = webContext.getWebComponentWithDefault(AsyncSupportRegistry.class, new AsyncSupportRegistry());
        this.bizPoolRegistry = webContext.getWebComponentWithDefault(BizPoolRegistry.class, new BizPoolRegistry());
        this.webFilterRegistry = webContext.getWebComponentWithDefault(WebFilterRegistry.class, new WebFilterRegistry(this));
        this.metrics = webContext.getWebComponentWithDefault(WebMetrics.class, NoOpWebMetrics.INSTANCE);
    }

    @Override
    public void httpHandle(WebServerHttpRequest req, WebServerHttpResponse resp) {
        handle(req, resp);
    }

    public void handle(WebServerHttpRequest req, WebServerHttpResponse resp) {
        // 路由匹配（EventLoop 中执行，路径查找 O(1)~O(n) 足够快）
        MappingResult result = mappingRegistry.mapping(req);
        handleWithMappingResult(req, resp, result);
    }

    protected void handleWithMappingResult(WebServerHttpRequest req, WebServerHttpResponse resp, MappingResult mappingResult) {
        // 通过 BizPoolRegistry 使用 Phase3 预缓存的线程池，无映射或未标注 @RunInPool 时为 null → EventLoop 同步处理
        ExecutorService executor = bizPoolRegistry.determinePool(req, mappingResult);
        if (executor != null) {
            req.acquire();
            try {
                executor.execute(() -> {
                    try {
                        handleWithFilter(req, resp, mappingResult);
                    } finally {
                        req.release();
                    }
                });
            } catch (RejectedExecutionException e) {
                req.release();
                if (!executor.isShutdown()) {
                    // 业务线程池负载过高（队列满 + 线程数已达上限），返回 503
                    // 不在 EventLoop 重试，避免阻塞 I/O 线程拖垮服务器
                    resp.getHeaders().set(HttpHeaders.RETRY_AFTER, "5");
                    sendError(resp, HttpStatus.SERVICE_UNAVAILABLE, "Too many requests");
                } else {
                    // 优雅关闭中，业务线程池已关闭，直接在 EventLoop 兜底执行
                    handleWithFilter(req, resp, mappingResult);
                }
            }
        } else {
            handleWithFilter(req, resp, mappingResult);
        }
    }

    /**
     * Filter 链处理完成后固定调用，根据映射结果决定走 doHandle 或 404/405。
     * 在此初始化上下文（如 LocaleContextHolder、RequestContextHolder），
     * 确保 Filter 链中对 request 的包装能被后续处理器正确获取。
     */
    public void handleAfterFilter(WebServerHttpRequest req, WebServerHttpResponse resp, MappingResult mappingResult) {
        boolean initContext = false;
        try {
            initContext = initContextHolders(req, resp);
            if (mappingResult.isMatched()) {
                doHandle(req, resp, mappingResult.getMatchedContext());
            } else {
                handleWithNoFullMatch(req, resp, mappingResult);
            }
        } finally {
            if (initContext) {
                removeContextHolders(req, resp);
            }
        }
    }

    protected void handleWithNoFullMatch(WebServerHttpRequest rq, WebServerHttpResponse rs, MappingResult mr) {
        // CORS 预检：路径匹配即可处理
        try {
            if (corsRegistry != null && CorsUtils.isPreFlightRequest(rq)) {
                handleCorsPreflight(rq, rs);
            } else {
                handleOnNoMatchMappingContext(rq, rs, mr);
            }
        } catch (Throwable e) {
            log.error("dispatcher error", e);
            sendError(rs, HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
        }
    }



    protected void handleOnNoMatchMappingContext(WebServerHttpRequest req, WebServerHttpResponse resp, MappingResult result) {
        // 404/405：抛出异常走 exceptionRegistry，与 doHandle 中异常路径行为一致
        ResponseStatusException ex = result.isMethodMismatch() ? METHOD_NOT_ALLOWED_EXCEPTION : NOT_FOUND_EXCEPTION;
        try {
            exceptionRegistry.handle(ex, req, resp);
        } finally {
            interceptorRegistry.afterCompletion(req, resp, ex);
        }
    }

    protected void handleCorsPreflight(WebServerHttpRequest req, WebServerHttpResponse resp) {
        try {
            if (corsRegistry.corsHandle(req, resp)) {
                resp.getBody().write(new byte[0]);
                resp.flush();
            }
        } catch (Exception e) {
            log.error("CORS preflight handling failed", e);
        }
    }

    /**
     * 在目标线程中执行完整的请求处理：Filter 链 → handleAfterFilter（含上下文初始化+清理）。
     */
    private void handleWithFilter(WebServerHttpRequest req, WebServerHttpResponse resp,
                                MappingResult mappingResult) {
        try {
            webFilterRegistry.doFilter(req, resp);
        } catch (Throwable ex) {
            handleException(ex, req, resp);
            invokeWithRealResult(req, resp, null, ex);
        }
    }

    protected void doHandle(WebServerHttpRequest req, WebServerHttpResponse resp,
                             PathMappingContext mappingContext) {
        Object result = null;
        Throwable exception = null;
        long start = metrics.getNanoTime();
        try {
            // cors
            if (corsRegistry.corsHandle(req, resp)) {
                resp.flush();
                return;
            }

            // --- preHandle ---
            if (!interceptorRegistry.preHandle(req, resp)) {
                resp.flush();
                return;
            }

            // resolve args
            Object[] args = argumentResolverRegistry.resolveArguments(mappingContext, req, resp);

            // invoke
            result = mappingContext.invoke(args, req, resp);

            // return value
            returnValueResolverRegistry.resolveReturnValue(result, mappingContext, req, resp);
        } catch (Throwable ex) {
            exception = ex;
            handleException(ex, req, resp);
        } finally {
            if (AsyncSupportUtils.isAsyncRequest(req)) {
                interceptorRegistry.afterConcurrentHandlingStarted(req, resp);
                req.getRequestContext().setAttribute(METRICS_START_ATTR, start);
            } else {
                invokeWithRealResult(req, resp, result, exception);
                metrics.recordRequest(req.getMethodValue(), mappingContext.getPathRule(),
                        resp.getStatus().value(), metrics.getNanoTime() - start);
            }
        }
    }

    protected void invokeWithRealResult(WebServerHttpRequest req, WebServerHttpResponse resp, Object result, Throwable exception) {
        try {
            // --- postHandle ---
            interceptorRegistry.postHandle(req, resp, result);
            // --- afterCompletion ---
            interceptorRegistry.afterCompletion(req, resp, exception);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            flushResponse(resp);
        }
    }

    /**
     * 统一异常处理（catch-safe）。
     *
     * @param ex   the exception to handle
     * @param req  the current HTTP request
     * @param resp the current HTTP response
     */
    protected void handleException(Throwable ex, WebServerHttpRequest req, WebServerHttpResponse resp) {
        log.error("Unhandled exception from request processing: {}", ex.getMessage(), ex);
        try {
            exceptionRegistry.handle(ex, req, resp);
        } catch (Throwable handleEx) {
            log.error("Exception handler failed", handleEx);
        }
    }

    /**
     * 刷新响应（catch-safe）。
     *
     * @param resp the HTTP response to flush
     */
    protected void flushResponse(WebServerHttpResponse resp) {
        try {
            if (resp.isHandled()) {
                resp.flush();
            }
        } catch (IOException e) {
            log.error("flushResponse failed: {}", e.getMessage(), e);
        }
    }

    public void asyncDispatch(WebServerHttpRequest req, WebServerHttpResponse resp, Object concurrentResult) {
        // Reset the handled flag — the initial request processing marked the response
        // as handled when the Callable/DeferredResult return value was resolved, but
        // the actual body hasn't been written yet.
        if (resp instanceof BaseWebServerHttpResponse) {
            ((BaseWebServerHttpResponse) resp).resetHandled();
        }

        Object result = null;
        Throwable exception = null;
        try {
            if (concurrentResult instanceof Throwable) {
                exception = (Throwable) concurrentResult;
                log.error("Async dispatch exception: {}", exception.getMessage(), exception);
                exceptionRegistry.handle(exception, req, resp);
            } else {
                result = concurrentResult;
                PathMappingContext ctx = PathMappingContext.get(req);
                if (ctx != null) {
                    returnValueResolverRegistry.resolveReturnValue(concurrentResult, ctx, req, resp);
                }
            }
        } catch (Throwable e) {
            log.error(e.getMessage(), e);
        } finally {
            invokeWithRealResult(req, resp, result, exception);
            Long start = req.getRequestContext().getAttribute(METRICS_START_ATTR);
            if (start != null) {
                PathMappingContext ctx = PathMappingContext.get(req);
                metrics.recordRequest(req.getMethodValue(),
                        ctx != null ? ctx.getPathRule() : null,
                        resp.getStatus().value(), metrics.getNanoTime() - start);
            }
        }
    }

    protected boolean initContextHolders(WebServerHttpRequest req, WebServerHttpResponse resp) {
        LocaleContext localeContext = buildLocaleContext(req, resp);
        if (localeContext != null) {
            LocaleContextHolder.setLocaleContext(localeContext, this.threadContextInheritable);
            return true;
        }
        return false;
    }

    protected void removeContextHolders(WebServerHttpRequest req, WebServerHttpResponse resp) {
        LocaleContextHolder.resetLocaleContext();
    }

    protected LocaleContext buildLocaleContext(WebServerHttpRequest req, WebServerHttpResponse resp) {
        return null;
    }

    protected static void sendError(WebServerHttpResponse resp, HttpStatus status, String reason) {
        try {
            resp.sendError(status, reason);
        } catch (Exception ignored) {
            log.warn("Failed to send error response", ignored);
        }
    }
}
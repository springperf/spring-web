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
import io.springperf.web.core.pool.BizPoolRegistry;
import io.springperf.web.core.retval.ReturnValueResolverRegistry;
import io.springperf.web.http.BaseWebServerHttpResponse;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.server.HttpHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
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
        this.webFilterRegistry = webContext.getWebComponentWithDefault(WebFilterRegistry.class, new WebFilterRegistry());
    }

    @Override
    public void httpHandle(WebServerHttpRequest req, WebServerHttpResponse resp) {
        handle(req, resp);
    }

    public void handle(WebServerHttpRequest req, WebServerHttpResponse resp) {
        // 路由匹配（EventLoop 中执行，路径查找 O(1)~O(n) 足够快）
        MappingResult result = mappingRegistry.mapping(req);
        if (result.isMatched()) {
            handleWithFullMatch(req, resp, result);
            return;
        }
        try {
            // 未匹配的请求先走 Filter 链（如 HealthFilter 等直接处理请求的 Filter）
            webFilterRegistry.doFilter(req, resp, result, this::handleWithNoFullMatch);
        } catch (Throwable e) {
            log.error("dispatcher error", e);
            sendError(resp, HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
        }
    }

    protected void handleWithFullMatch(WebServerHttpRequest req, WebServerHttpResponse resp, MappingResult mappingResult) {
        // 通过 BizPoolRegistry 使用 Phase3 预缓存的线程池，无注解时为 null → 在 EventLoop 中同步处理
        ExecutorService executor = bizPoolRegistry.determinePool(mappingResult.getMatchedContext());
        if (executor != null) {
            req.acquire();
            try {
                executor.execute(() -> {
                    try {
                        processRequest(req, resp, mappingResult);
                    } finally {
                        req.release();
                    }
                });
            } catch (RejectedExecutionException e) {
                req.release();
                throw e;
            }
        } else {
            processRequest(req, resp, mappingResult);
        }
    }

    protected void handleWithNoFullMatch(WebServerHttpRequest rq, WebServerHttpResponse rs, MappingResult mr) {
        // CORS 预检：路径匹配即可处理
        try {
            if (corsRegistry != null && CorsUtils.isPreFlightRequest(rq)) {
                handleCorsPreflight(rq, rs, mr.isPathMatched() ? mr.getPathMatchedContexts() : null);
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

    protected void handleCorsPreflight(WebServerHttpRequest req, WebServerHttpResponse resp,
                                     PathMappingContext[] pathMatchedContexts) {
        try {
            // 从路径命中的 context 中取第一个用于读取 @CrossOrigin 配置
            if (pathMatchedContexts != null && pathMatchedContexts.length > 0) {
                PathMappingContext.set(req, pathMatchedContexts[0]);
            }
            if (corsRegistry.corsHandle(req, resp)) {
                resp.getBody().write(new byte[0]);
                resp.flush();
            }
        } catch (Exception e) {
            log.error("CORS preflight handling failed", e);
        }
    }

    /**
     * 在目标线程中执行完整的请求处理：上下文初始化 → Filter 链 → doHandle → 清理。
     */
    private void processRequest(WebServerHttpRequest req, WebServerHttpResponse resp,
                                MappingResult mappingResult) {
        boolean initContext = false;
        try {
            initContext = initContextHolders(req, resp);
            webFilterRegistry.doFilter(req, resp, mappingResult, (rq, rs, mr) -> doHandle(rq, rs, mr.getMatchedContext()));
        } catch (Throwable ex) {
            // Filter 链中抛出的异常，与 doHandle 中异常路径行为一致
            handleException(ex, req, resp);
            invokeWithRealResult(req, resp, null, ex);
        } finally {
            if (initContext) {
                removeContextHolders(req, resp);
            }
        }
    }

    protected void doHandle(WebServerHttpRequest req, WebServerHttpResponse resp,
                             PathMappingContext mappingContext) {
        Object result = null;
        Throwable exception = null;
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
            } else {
                invokeWithRealResult(req, resp, result, exception);
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
        log.error(ex.getMessage(), ex);
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
            log.error(e.getMessage(), e);
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
                log.error(exception.getMessage(), exception);
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

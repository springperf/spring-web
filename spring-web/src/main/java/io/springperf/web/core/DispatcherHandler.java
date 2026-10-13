package io.springperf.web.core;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.ArgumentResolverRegistry;
import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.core.async.AsyncSupportUtils;
import io.springperf.web.core.cors.CorsRegistry;
import io.springperf.web.core.cors.CorsUtils;
import io.springperf.web.core.exception.ExceptionRegistry;
import io.springperf.web.core.exception.StacklessResponseStatusException;
import io.springperf.web.core.filter.WebFilterRegistry;
import io.springperf.web.core.interceptor.InterceptorRegistry;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.match.HttpMethodMatcher;
import io.springperf.web.core.mapping.match.Matcher;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Central dispatcher: route lookup, optional offload to business thread pool, argument resolution, handler method invocation, and return value processing.
 */
@Slf4j
public class DispatcherHandler extends BaseWebComponent implements HttpHandler {
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

    /** Locale 解析配置（{@code spring.web.locale} / {@code locale-resolver} / {@code locale-bind}），启动期预解析。 */
    private LocaleConfig localeConfig = LocaleConfig.DEFAULT;

    /**
     * {@code spring.mvc.publish-request-handled-events}：请求处理完成后是否发布
     * {@code ServletRequestHandledEvent}。默认 {@code false}（无监听方时每请求发布纯属开销）。
     * 发布器在启动期从 {@link WebContext} 解析。
     */
    private boolean publishRequestHandledEvents = false;
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    /**
     * 对齐 {@code spring.mvc.throw-exception-if-no-handler-found}：默认 {@code true}（保持框架既有行为——404/405 进入异常解析，
     * 可被 {@code @ControllerAdvice} 拦截）；设为 {@code false} 时直接 {@code sendError}，不进入 {@link ExceptionRegistry}。
     */
    private boolean throwExceptionIfNoHandlerFound = true;

    /**
     * 对齐 {@code spring.mvc.dispatch.error/options/trace}：是否为对应 HTTP 方法分发处理器（默认均 true）。
     * 关闭时该方法的请求不进入 {@code @RequestMapping} 匹配（OPTIONS 仍可回退 CORS 预检；ERROR/TRACE 直接 404）。
     */
    private boolean dispatchError = true;
    private boolean dispatchOptions = true;
    private boolean dispatchTrace = true;

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
        // 启动期预解析 Locale 与事件发布开关；getProps() 可能为 null（单元测试 mock），此时回退默认值
        ApplicationProperties props = webContext.getProps();
        if (props != null) {
            // 启动期预解析 Locale 配置（spring.web.locale / locale-resolver / locale-bind）
            this.localeConfig = LocaleConfig.fromProperties(props);
            // 启动期预解析「发布请求处理完成事件」开关
            this.publishRequestHandledEvents = props.getBoolean(
                    PropertiesConstant.MVC_PUBLISH_REQUEST_HANDLED_EVENTS,
                    PropertiesConstant.MVC_PUBLISH_REQUEST_HANDLED_EVENTS_DEFAULT);
            // 启动期预解析 404/405 行为开关（默认 true，保持既有「进入异常解析」行为）
            this.throwExceptionIfNoHandlerFound = props.getBoolean(
                    PropertiesConstant.THROW_EXCEPTION_IF_NO_HANDLER_FOUND,
                    PropertiesConstant.THROW_EXCEPTION_IF_NO_HANDLER_FOUND_DEFAULT);
            // 启动期预解析 ERROR/OPTIONS/TRACE 分发开关（默认均 true，保持既有「全方法分发」行为）
            this.dispatchError = props.getBoolean(PropertiesConstant.MVC_DISPATCH_ERROR,
                    PropertiesConstant.MVC_DISPATCH_ERROR_DEFAULT);
            this.dispatchOptions = props.getBoolean(PropertiesConstant.MVC_DISPATCH_OPTIONS,
                    PropertiesConstant.MVC_DISPATCH_OPTIONS_DEFAULT);
            this.dispatchTrace = props.getBoolean(PropertiesConstant.MVC_DISPATCH_TRACE,
                    PropertiesConstant.MVC_DISPATCH_TRACE_DEFAULT);
        }
        // 请求处理完成事件的发布器：优先取 WebContext 持有的 ApplicationContext。
        // webContext 是可被测试替身替换的协作者，运行期不保证满足静态类型，故按容错契约保留判断。
        if (publishRequestHandledEvents
                && webContext.getCtx() instanceof org.springframework.context.ApplicationEventPublisher) {
            this.eventPublisher = (org.springframework.context.ApplicationEventPublisher) webContext.getCtx();
        }
    }

    @Override
    public void httpHandle(WebServerHttpRequest req, WebServerHttpResponse resp) {
        handle(req, resp);
    }

    public void handle(WebServerHttpRequest req, WebServerHttpResponse resp) {
        // spring.mvc.dispatch.error/options/trace：关闭时为对应方法短路，不进入路由匹配。
        // OPTIONS 关闭时仍放行 CORS 预检（交由 handleWithNoFullMatch 的预检分支处理）。
        if (!isMethodDispatchEnabled(req)) {
            MappingResult unmatched = MappingResult.notFound();
            MappingResult.set(req, unmatched);
            handleWithMappingResult(req, resp, unmatched);
            return;
        }
        // 路由匹配（EventLoop 中执行，路径查找 O(1)~O(n) 足够快）
        MappingResult result = mappingRegistry.mapping(req);
        handleWithMappingResult(req, resp, result);
    }

    /** dispatch 开关闸门方法名（5 字符：ERROR / TRACE）。 */
    private static final String GATED_ERROR = "ERROR";
    private static final String GATED_TRACE = "TRACE";
    /** dispatch 开关闸门方法名（7 字符：OPTIONS）。 */
    private static final String GATED_OPTIONS = "OPTIONS";

    /**
     * 按 {@code spring.mvc.dispatch.*} 判断当前请求方法是否允许分发给处理器。
     * 未禁用的方法恒返回 true；被禁用的 OPTIONS 在 CORS 预检场景仍放行。
     */
    protected boolean isMethodDispatchEnabled(WebServerHttpRequest req) {
        // 用方法名字符串比较：ERROR 非标准 HTTP 方法，HttpMethod 枚举无法表示。
        String methodValue = req.getMethodValue();
        if (methodValue == null) {
            return true;
        }
        // 热路径（GET/POST/PUT/PATCH...）零扫描放行：equalsIgnoreCase 先比长度，长度不符立即返回，
        // 不产生任何逐字符工作与分配（原实现先 toUpperCase(Locale.ROOT) 整串扫描再查表）。
        // 方法 token 不保证大写（Netty 对未知方法原样保留），故必须大小写不敏感，
        // 否则小写 "trace" 会绕过 dispatch.trace=false 的拦截。
        int len = methodValue.length();
        if (len == 5) {
            if (methodValue.equalsIgnoreCase(GATED_ERROR)) {
                return dispatchError;
            }
            if (methodValue.equalsIgnoreCase(GATED_TRACE)) {
                return dispatchTrace;
            }
            return true;
        }
        if (len == 7 && methodValue.equalsIgnoreCase(GATED_OPTIONS)) {
            // 关闭 OPTIONS 分发，但 CORS 预检仍需处理（框架级能力，非 @RequestMapping）
            return dispatchOptions || CorsUtils.isPreFlightRequest(req);
        }
        return true;
    }

    /** 仅供测试：暴露 mappingRegistry（包可见，不进公开 API）。 */
    MappingRegistry mappingRegistryForTest() {
        return mappingRegistry;
    }

    protected void handleWithMappingResult(WebServerHttpRequest req, WebServerHttpResponse resp, MappingResult mappingResult) {
        // 通过 BizPoolRegistry 用 Phase3 预缓存的线程池：返回 null（无映射 / @RunInPool(EVENTLOOP) / default-execute-mode=eventloop）→ EventLoop 同步；返回非 null（缺省 default 池 / @RunInPool 命名池）→ 切业务线程
        ExecutorService executor = bizPoolRegistry.determinePool(req, mappingResult);
        if (executor != null) {
            // 交棒到业务线程池：EventLoop 随之空闲，响应超时定时器自此才真正可能触发
            // （EventLoop 默认模式下请求开始未装配，见 NettyHttpHandler#armTimeoutOnRequestStart）。
            // 幂等：非 EventLoop 默认模式已在请求开始装配，此处为 no-op（不触发 cancel/reschedule）。
            resp.armTimeoutIfAbsent();
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
            // 请求处理完成，按 spring.mvc.publish-request-handled-events 发布事件
            publishRequestHandledEvent(req, resp, mappingResult);
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
        // 路径匹配但条件不满足：按原因映射状态码（对齐 Spring MVC）
        // METHOD→405（含 Allow 头）、CONSUMES→415、PRODUCES→406、其余→404
        MappingResult.MismatchKind kind = result.getMismatchKind();
        HttpStatus status;
        if (kind == MappingResult.MismatchKind.METHOD) {
            status = HttpStatus.METHOD_NOT_ALLOWED;
        } else if (kind == MappingResult.MismatchKind.CONSUMES) {
            status = HttpStatus.UNSUPPORTED_MEDIA_TYPE;
        } else if (kind == MappingResult.MismatchKind.PRODUCES) {
            status = HttpStatus.NOT_ACCEPTABLE;
        } else {
            status = HttpStatus.NOT_FOUND;
        }
        boolean methodNotAllowed = status == HttpStatus.METHOD_NOT_ALLOWED;
        if (!throwExceptionIfNoHandlerFound) {
            // 对齐 Spring Boot 默认行为：直接 sendError，不进入 ExceptionRegistry / @ControllerAdvice
            // （避免用户未显式开启时意外拦截 404）。
            if (methodNotAllowed) {
                setAllowHeader(resp, result);
            }
            sendError(resp, status, status.getReasonPhrase());
            interceptorRegistry.afterCompletion(req, resp, null);
            return;
        }
        // 每请求新建异常（fillInStackTrace 已禁用，零栈轨迹开销），避免复用单例导致
        // @ExceptionHandler 修改 headers/body 污染后续请求。
        // 注：core 不依赖 jakarta.servlet-api，无法构造 Spring 的
        // HttpRequestMethodNotSupportedException / HttpMediaTypeNotSupportedException，
        // 统一以 ResponseStatusException 承载状态码，Allow 等语义头在此直接写入
        // （@ControllerAdvice 仍可按 ResponseStatusException 拦截）。
        if (methodNotAllowed) {
            setAllowHeader(resp, result);
        }
        ResponseStatusException ex = new StacklessResponseStatusException(status);
        try {
            exceptionRegistry.handle(ex, req, resp);
            // advice/异常解析器可能只写 body 不 commit（sendError 才内置 flush）——此处兜底提交，
            // 否则 @ControllerAdvice 定制的 404/405 响应永不发出（客户端挂死）
            flushResponse(req, resp);
        } finally {
            interceptorRegistry.afterCompletion(req, resp, ex);
            flushResponse(req, resp);
        }
    }

    /** 405 语义：写出 Allow 头，列出该路径已注册的全部方法（对齐 Spring HttpRequestMethodNotSupportedException）。 */
    private static void setAllowHeader(WebServerHttpResponse resp, MappingResult result) {
        Set<HttpMethod> methods = supportedMethods(result);
        if (!methods.isEmpty()) {
            resp.getHeaders().setAllow(methods);
        }
    }

    /** 收集路径命中的所有映射上下文支持的方法（用于 405 的 Allow 头）。 */
    private static Set<HttpMethod> supportedMethods(MappingResult result) {
        Set<HttpMethod> methods = new LinkedHashSet<>();
        PathMappingContext[] contexts = result.getPathMatchedContexts();
        if (contexts != null) {
            for (PathMappingContext ctx : contexts) {
                for (Matcher matcher : ctx.getMatchers()) {
                    if (matcher instanceof HttpMethodMatcher) {
                        methods.addAll(((HttpMethodMatcher) matcher).getHttpMethods());
                    }
                }
            }
        }
        return methods;
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
        boolean initContext = false;
        try {
            webFilterRegistry.doFilter(req, resp);
        } catch (Throwable ex) {
            // Filter 链内抛异常时，handleAfterFilter（正常路径的上下文初始化点）不会执行。
            // 这里与 handleAfterFilter 对称：初始化上下文后走异常处理与 afterCompletion，
            // 结束时清理，避免异常处理器/拦截器读到 null 或上一线程残留的 ThreadLocal 值。
            initContext = initContextHolders(req, resp);
            try {
                handleException(ex, req, resp);
                invokeWithRealResult(req, resp, null, ex);
            } finally {
                if (initContext) {
                    removeContextHolders(req, resp);
                }
            }
        }
    }

    protected void doHandle(WebServerHttpRequest req, WebServerHttpResponse resp,
                             PathMappingContext mappingContext) {
        Object result = null;
        Throwable exception = null;
        long start = metrics.getNanoTime();
        boolean preHandlePassed = false;
        try {
            // cors
            if (corsRegistry.corsHandle(req, resp)) {
                finalizeEarlyReturn(req, resp);
                return;
            }

            // --- preHandle ---
            preHandlePassed = interceptorRegistry.preHandle(req, resp);
            if (!preHandlePassed) {
                // Spring 语义（HandlerExecutionChain.applyPreHandle）：preHandle 返回 false 时
                // afterCompletion 仅对已通过的拦截器执行（已在 InterceptorRegistry.preHandle 内
                // 完成），不执行 postHandle，也不再次全量调用 afterCompletion。修复前 finally 里的
                // invokeWithRealResult 无条件再回调一次 → 已通过者双调、未进入者误收回调。
                finalizeEarlyReturn(req, resp);
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
                try {
                    AsyncSupportUtils.getAsyncWebRequest(req, resp).executeAsyncReadyCallback();
                } catch (Throwable ex) {
                    handleException(ex, req, resp);
                }
            } else {
                // preHandle 未通过（返回 false 或抛异常）时，afterCompletion 已由
                // InterceptorRegistry.preHandle 对已通过者回调完毕，此处跳过全量回调
                if (preHandlePassed) {
                    invokeWithRealResult(req, resp, result, exception);
                }
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
            flushResponse(req, resp);
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
     * @param req  the current HTTP request
     * @param resp the HTTP response to flush
     */
    protected void flushResponse(WebServerHttpRequest req, WebServerHttpResponse resp) {
        try {
            if (resp.isStreaming()) {
                // 渐进式输出（servlet flushBuffer/writer.flush 或 flush(true) 进入）：
                // 收尾必须写终止块，否则客户端无法判定响应结束（挂到读超时）。
                // 异步请求仍在挂起时不得终止（SSE/流式 emitter 还在持续写事件），
                // 待其完成时的本轮收尾再终止；外部 sender 已收尾的由 markStreamCompleted 兜底为空操作。
                if (isAsyncStillPending(req)) {
                    return;
                }
                resp.endStream();
                return;
            }
            // 已提交（一次性写出路径已落盘，如 byte[] → writeBytes）则无需再刷：
            // 再调 flush 会走到 writeAndFlush 的「已提交」拒绝分支，释放缓冲并打 WARN——
            // 每个 byte[] 响应都白刷一次 + 白打一条 WARN。此处直接跳过。
            if (resp.isHandled() && !resp.isCommitted()) {
                resp.flush();
            }
        } catch (IOException e) {
            log.error("flushResponse failed: {}", e.getMessage(), e);
        }
    }

    /** 异步请求是否仍处于挂起状态（已启动但尚未完成/派发结果）。 */
    private static boolean isAsyncStillPending(WebServerHttpRequest req) {
        try {
            io.springperf.web.core.async.PerfAsyncWebRequest async = req.getRequestContext()
                    .getAttribute(AsyncSupportUtils.WEB_ASYNC_REQUEST_ATTRIBUTE);
            // DISPATCHED（结果已派发、响应仍在写出/流式发送中）同样属「尚未结束」：
            // 此时收尾若调用 endStream() 会与 StreamSender 争写终止块（双写 → 编码器 state: 0）
            return async != null && (async.isAsyncStarted() || async.isAsyncDispatched());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 提前返回（CORS 预检 / preHandle 拦截）时的响应收尾。
     *
     * <p>拦截器可通过 servlet 的 {@code getWriter().flush()} 提交响应而进入 chunked 渐进式输出
     * （Servlet 规范：flush 即提交）；此时必须写终止块，否则客户端无法判定响应结束（挂到读超时）。
     * 非流式路径保持原 {@code resp.flush()} 语义（未提交则一次性提交，已提交则空操作）。</p>
     */
    private void finalizeEarlyReturn(WebServerHttpRequest req, WebServerHttpResponse resp) throws IOException {
        if (resp.isStreaming()) {
            flushResponse(req, resp);
            return;
        }
        resp.flush();
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
            exception = e;
            log.error("Async dispatch exception: {}", e.getMessage(), e);
            try {
                exceptionRegistry.handle(e, req, resp);
            } catch (Throwable handleEx) {
                log.error("Async exception handler failed", handleEx);
            }
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
        // spring.web.locale-bind=false：完全不触碰 LocaleContextHolder
        // （省掉每请求的上下文分配与 ThreadLocal set/remove；读取时 Spring 回退 JVM 默认 Locale）
        if (!localeConfig.isBindEnabled()) {
            return false;
        }
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
        // 按 spring.web.locale / locale-resolver 解析（fixed 固定 Locale；accept-header 按请求头）
        return localeConfig.resolveLocaleContext(req);
    }

    /**
     * 发布 {@code ServletRequestHandledEvent}（对齐 Boot {@code spring.mvc.publish-request-handled-events}）。
     * 无发布器、开关关闭时静默跳过；发布失败不影响请求处理结果。
     */
    protected void publishRequestHandledEvent(WebServerHttpRequest req, WebServerHttpResponse resp,
                                             MappingResult mappingResult) {
        if (!publishRequestHandledEvents || eventPublisher == null) {
            return;
        }
        try {
            String requestUrl = req.getUriStr();
            String shortDesc = mappingResult != null && mappingResult.getMatchedContext() != null
                    ? mappingResult.getMatchedContext().getPathRule()
                    : requestUrl;
            int status = resp.getStatus() != null ? resp.getStatus().value() : 200;
            // (source, requestUrl, clientAddress, method, servletName, sessionId, userName,
            // processingTimeMillis, failureCause, statusCode)
            eventPublisher.publishEvent(new org.springframework.web.context.support.ServletRequestHandledEvent(this,
                    requestUrl, null, req.getMethodValue(), shortDesc, null, null, -1L, null, status));
        } catch (Throwable ex) {
            log.debug("publish ServletRequestHandledEvent failed", ex);
        }
    }

    protected static void sendError(WebServerHttpResponse resp, HttpStatus status, String reason) {
        try {
            resp.sendError(status, reason);
        } catch (Exception ignored) {
            log.warn("Failed to send error response", ignored);
        }
    }
}
package io.springperf.web.core.exception;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.web.method.HandlerMethod;

import io.springperf.web.context.WebComponentContainer;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.metrics.NoOpWebMetrics;
import io.springperf.web.core.metrics.WebMetrics;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.server.ErrorResponseConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * Scans @ExceptionHandler methods in @ControllerAdvice and resolves exceptions by the most specific matching type.
 * Falls back to 500 with a brief message when no match is found.
 */
@Slf4j
public class ExceptionRegistry extends WebComponentContainer {

    protected final List<HandlerExceptionResolver> resolvers = new CopyOnWriteArrayList<>();
    protected WebMetrics metrics;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        this.metrics = webContext.getWebComponentWithDefault(WebMetrics.class, NoOpWebMetrics.INSTANCE);
        registerWebComponent(new ExceptionHandlerExceptionResolver());
        registerWebComponent(new ResponseStatusExceptionResolver());
        registerWebComponent(HandlerExceptionResolver.class);
    }

    @Override
    public void initComponentPhase2() throws Exception {
        super.initComponentPhase2();
        initRealComponentList(resolvers, HandlerExceptionResolver.class);
    }

    /**
     * 500 兜底响应暴露的 message：取**根因**的 message（对齐 Boot {@code DefaultErrorAttributes#addErrorMessage}）。
     * <p>
     * 固定文案「Internal Server Error」会让 {@code server.error.include-message=always} 形同虚设 ——用户显式要求暴露 message
     * 时拿到的却是与状态码重复的套话，真实原因只藏在 trace 里。
     * </p>
     */
    private static String rootCauseMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message != null && !message.isEmpty() ? message : "Internal Server Error";
    }

    public void handle(Throwable ex, WebServerHttpRequest req, WebServerHttpResponse resp) {
        try {
            boolean handled = doHandle(ex, req, resp);
            metrics.recordException(ex.getClass().getName(), handled);
            if (handled) {
                resp.setHandled();
            } else {
                // 未匹配任何 @ExceptionHandler：回退 500 错误页，携带原始异常供 server.error.* 策略
                // 决定是否暴露栈/message/绑定错误（on-param 由请求参数 trace/message/errors 命中决定，
                // 参数名对齐 Boot AbstractErrorController）。
                resp.sendError(INTERNAL_SERVER_ERROR, rootCauseMessage(ex), ex,
                        ErrorResponseConfig.isParamPresent(req, "trace"),
                        ErrorResponseConfig.isParamPresent(req, "message"),
                        ErrorResponseConfig.isParamPresent(req, "errors"));
            }
        } catch (Exception e) {
            log.error("ExceptionRegistry.doHandle/sendError failed for original [{}] {}", ex.getClass().getSimpleName(),
                    ex.getMessage(), e);
        }
    }

    public boolean doHandle(Throwable ex, WebServerHttpRequest req, WebServerHttpResponse resp) {
        HandlerMethod handlerMethod = PathMappingContext.get(req);
        for (HandlerExceptionResolver resolver : resolvers) {
            if (resolver.resolveException(req, resp, handlerMethod, ex)) {
                return true;
            }
        }
        return false;
    }

    public void addResolver(HandlerExceptionResolver resolver) {
        registerWebComponent(resolver);
        initRealComponentList(resolvers, HandlerExceptionResolver.class);
    }
}

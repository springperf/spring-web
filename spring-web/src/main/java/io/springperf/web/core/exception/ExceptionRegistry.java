package io.springperf.web.core.exception;

import io.springperf.web.context.WebComponentContainer;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.method.HandlerMethod;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

/**
 * Scans @ExceptionHandler methods in @ControllerAdvice and resolves exceptions by the most specific matching type.
 * Falls back to 500 with a brief message when no match is found.
 */
@Slf4j
public class ExceptionRegistry extends WebComponentContainer {

    protected final List<HandlerExceptionResolver> resolvers = new ArrayList<>();

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        registerWebComponent(new ExceptionHandlerExceptionResolver());
        registerWebComponent(new ResponseStatusExceptionResolver());
        registerWebComponent(HandlerExceptionResolver.class);
    }

    @Override
    public void initComponentPhase2() throws Exception {
        super.initComponentPhase2();
        initRealComponentList(resolvers, HandlerExceptionResolver.class);
    }

    public void handle(Throwable ex, WebServerHttpRequest req, WebServerHttpResponse resp) {
        try {
            boolean handled = doHandle(ex, req, resp);
            if (handled) {
                resp.setHandled();
            } else {
                resp.sendError(INTERNAL_SERVER_ERROR, ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        } catch (Exception e) {
            log.error("ExceptionRegistry.doHandle/sendError failed for original [{}] {}",
                    ex.getClass().getSimpleName(), ex.getMessage(), e);
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

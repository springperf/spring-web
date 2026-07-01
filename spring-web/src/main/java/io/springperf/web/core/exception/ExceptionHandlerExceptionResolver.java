package io.springperf.web.core.exception;

import io.springperf.web.context.WebComponentContainer;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.ArgumentResolverRegistry;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.provider.StaticArgumentResolverProvider;
import io.springperf.web.core.mapping.MappingCacheKey;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.retval.ReturnValueResolverRegistry;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

@Slf4j
public class ExceptionHandlerExceptionResolver extends WebComponentContainer implements HandlerExceptionResolver {

    protected static final MappingCacheKey<ExceptionHandlerAdvice[]> MAPPING_CACHE_KEY = MappingCacheKey.createClassCacheKey(ExceptionHandlerAdvice[].class);

    protected final List<ExceptionHandlerAdvice> exceptionHandlerAdvices = new ArrayList<>();

    protected static final String EXCEPTION_OBJECT_KEY = ExceptionHandlerExceptionResolver.class.getName() + "_KEY";
    protected ArgumentResolverRegistry argumentResolverRegistry;
    protected ReturnValueResolverRegistry returnValueResolverRegistry;

    protected void initExceptionHandler() {
        List<ControllerAdviceBean> adviceBeans = ControllerAdviceBean.findAnnotatedBeans(webContext.getCtx());
        for (ControllerAdviceBean adviceBean : adviceBeans) {
            Class<?> beanType = adviceBean.getBeanType();
            if (beanType == null) {
                throw new IllegalStateException("Unresolvable type for ControllerAdviceBean: " + adviceBean);
            }
            ExceptionHandlerMethodResolver resolver = new ExceptionHandlerMethodResolver(beanType);
            if (resolver.hasExceptionMappings()) {
                registerWebComponent(new ExceptionHandlerAdvice(adviceBean, resolver));
            }
        }
    }

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        this.argumentResolverRegistry = webContext.getWebComponentWithDefault(ArgumentResolverRegistry.class, new ArgumentResolverRegistry());
        this.argumentResolverRegistry.addStaticArgumentResolverProvider(new ExceptionArgumentResolverProvider());
        this.returnValueResolverRegistry = webContext.getWebComponentWithDefault(ReturnValueResolverRegistry.class, new ReturnValueResolverRegistry());
        initExceptionHandler();
    }

    @Override
    public void initComponentPhase2() throws Exception {
        super.initComponentPhase2();
        initRealComponentList(exceptionHandlerAdvices, ExceptionHandlerAdvice.class);
    }


    /**
     * ResponseStatusException 是框架内部信号异常，不应被宽泛的父类匹配意外拦截。
     * 只有 {@code @ExceptionHandler(ResponseStatusException.class)} 及其子类显式声明时才处理，
     * {@code @ExceptionHandler(Throwable.class)}、{@code @ExceptionHandler(RuntimeException.class)} 等父类声明跳过。
     * <p>结果缓存到 MappingHandlerMethod 上，首次反射计算后不再重复读取注解。</p>
     */
    private static final MappingCacheKey<Boolean> RSE_EXPLICIT_CACHE_KEY = MappingCacheKey.createMethodCacheKey(Boolean.class);

    static boolean isExplicitRseHandler(MappingHandlerMethod handlerMethod) {
        Boolean cached = handlerMethod.get(RSE_EXPLICIT_CACHE_KEY);
        if (cached != null) return cached;

        Method method = handlerMethod.getMethod();
        ExceptionHandler ann = method.getAnnotation(ExceptionHandler.class);
        boolean result = false;
        if (ann != null) {
            for (Class<?> type : ann.value()) {
                if (ResponseStatusException.class.isAssignableFrom(type)) {
                    result = true;
                    break;
                }
            }
        }
        handlerMethod.set(RSE_EXPLICIT_CACHE_KEY, result);
        return result;
    }

    protected static final ExceptionHandlerMethodResolver NO_MATCH = new ExceptionHandlerMethodResolver(Object.class);
    private static final Map<Class<?>, ExceptionHandlerMethodResolver> exceptionHandlerCache = new ConcurrentHashMap<>(64);

    @Override
    public boolean resolveException(WebServerHttpRequest request, WebServerHttpResponse response, @Nullable HandlerMethod handler, Throwable ex) {
        ExceptionHandlerAdvice[] cachedAdvices = getCachedExceptionHandlerAdvices(PathMappingContext.get(request));
        if (cachedAdvices != null) {
            for (ExceptionHandlerAdvice exceptionHandlerAdvice : cachedAdvices) {
                MappingHandlerMethod handlerMethod = exceptionHandlerAdvice.resolveHandlerMethod(ex);
                if (handlerMethod != null) {
                    if (ex instanceof ResponseStatusException && !isExplicitRseHandler(handlerMethod)) {
                        continue;
                    }
                    invokeAndWriteError(handlerMethod, ex, request, response);
                    return true;
                }
            }
            return false;
        }
        Class<?> handlerType = handler == null ? null : handler.getBeanType();
        for (ExceptionHandlerAdvice exceptionHandlerAdvice : exceptionHandlerAdvices) {
            if (exceptionHandlerAdvice.isApplicableToBeanType(handlerType)) {
                MappingHandlerMethod handlerMethod = exceptionHandlerAdvice.resolveHandlerMethod(ex);
                if (handlerMethod != null) {
                    if (ex instanceof ResponseStatusException && !isExplicitRseHandler(handlerMethod)) {
                        continue;
                    }
                    invokeAndWriteError(handlerMethod, ex, request, response);
                    return true;
                }
            }
        }
        return false;
    }

    protected void invokeAndWriteError(MappingHandlerMethod handlerMethod, Throwable ex, WebServerHttpRequest request, WebServerHttpResponse response) {
        try {
            request.getRequestContext().setAttribute(EXCEPTION_OBJECT_KEY, ex);
            Object[] arguments = argumentResolverRegistry.resolveArguments(handlerMethod, request, response);
            Object r = handlerMethod.invoke(arguments, request, response);
            if (response.isHandled()) {
                return;
            }
            if (r != null) {
                returnValueResolverRegistry.resolveReturnValue(r, handlerMethod, request, response);
            }
            // r == null && !handled: void @ExceptionHandler 方法视为已处理，不额外发送错误
        } catch (Throwable e) {
            log.error("Exception handler failed", e);
            try {
                response.sendError(INTERNAL_SERVER_ERROR, "Exception handler failed");
            } catch (Exception ignored) {
                log.warn("Failed to send error response in exception handler", ignored);
            }
        }
    }

    protected ExceptionHandlerAdvice[] getCachedExceptionHandlerAdvices(MappingHandlerMethod handlerMethod) {
        if (handlerMethod == null) {
            return null;
        }
        Class<?> handlerType = handlerMethod.getBeanType();
        ExceptionHandlerAdvice[] cachedExceptionHandlerAdvices = handlerMethod.get(MAPPING_CACHE_KEY);
        if (cachedExceptionHandlerAdvices == null) {
            List<ExceptionHandlerAdvice> exceptionHandlerAdvices = new ArrayList<>();
            ExceptionHandlerMethodResolver resolver = exceptionHandlerCache.get(handlerType);
            if (resolver == null) {
                resolver = new ExceptionHandlerMethodResolver(handlerType);
                if (!resolver.hasExceptionMappings()) {
                    resolver = NO_MATCH;
                }
                exceptionHandlerCache.put(handlerType, resolver);
            }
            if (resolver.hasExceptionMappings()) {
                exceptionHandlerAdvices.add(new ExceptionHandlerAdvice(handlerMethod.getBean(), resolver));
            }
            for (ExceptionHandlerAdvice advice : this.exceptionHandlerAdvices) {
                if (advice.isApplicableToBeanType(handlerType)) {
                    exceptionHandlerAdvices.add(advice);
                }
            }
            cachedExceptionHandlerAdvices = exceptionHandlerAdvices.toArray(new ExceptionHandlerAdvice[0]);
            handlerMethod.set(MAPPING_CACHE_KEY, cachedExceptionHandlerAdvices);
        }
        return cachedExceptionHandlerAdvices;
    }

    protected static class ExceptionArgumentResolverProvider implements StaticArgumentResolverProvider {

        private static final StaticArgumentResolver resolver = (req, resp) -> req.getRequestContext().getAttribute(EXCEPTION_OBJECT_KEY);

        @Override
        public boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext) {
            return Throwable.class.isAssignableFrom(parameter.getParameterType()) && MappingHandlerMethod.class.equals(mappingContext.getClass());
        }

        @Override
        public StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
            return resolver;
        }
    }
}

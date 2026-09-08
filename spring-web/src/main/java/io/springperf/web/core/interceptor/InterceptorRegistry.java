package io.springperf.web.core.interceptor;

import io.springperf.web.context.WebComponentContainer;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.util.support.ContainmentResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationAwareOrderUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.method.ControllerAdviceBean;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the registration and lifecycle of HandlerInterceptors, supporting path-pattern-based
 * matching and runtime resolution. 亦支持"方法级/类级"匹配：实现 {@link HandlerInterceptor} 并标注
 * {@link org.springframework.web.bind.annotation.ControllerAdvice} 的拦截器，会被包装
 * {@link ControllerAdviceBean}，按 handler 的 controller 类型（而非 path）匹配。
 */
@Slf4j
public class InterceptorRegistry extends WebComponentContainer {

    public static final RequestAttribute<List<HandlerInterceptor>> INTERCEPTORS_ATTRIBUTE =
            (RequestAttribute) RequestAttribute.createAttribute(List.class);
    private final List<InterceptorRegistration> registrations = new ArrayList<>();

    private final List<HandlerInterceptor> runtimeMappingInterceptors = new ArrayList<>();

    public InterceptorRegistry() {
        autoRegisterWebComponent(InterceptorRegistration.class);
        autoRegisterWebComponent(HandlerInterceptor.class, this::registerInterceptor);
    }

    @Override
    public void initComponentPhase2() throws Exception {
        super.initComponentPhase2();
        initRealComponentList(registrations, InterceptorRegistration.class);
        runtimeMappingInterceptors.clear();
        // runtimeMappingInterceptors 仅在 mappingContext 为 null（404/405 无 handler）时兜底，
        // 方法级拦截器（@ControllerAdvice 类级匹配）依赖 handler 的 controller 类型，此处一律跳过。
        registrations.stream()
                .filter(registration -> !registration.isControllerAdviceScoped())
                .map(this::getRuntimeMappingInterceptor)
                .forEach(runtimeMappingInterceptors::add);
    }

    protected InterceptorRegistration registerInterceptor(HandlerInterceptor interceptor) {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);
        // 类级匹配：拦截器标注 @ControllerAdvice 时包装 ControllerAdviceBean（按 controller 类型匹配）
        ControllerAdviceBean adviceBean = findControllerAdviceBean(interceptor);
        if (adviceBean != null) {
            registration.applyTo(adviceBean);
        }
        registrations.add(registration);
        Integer order = AnnotationAwareOrderUtils.findOrder(interceptor);
        if (order != null) {
            registration.order(order);
        }
        return registration;
    }

    /**
     * 查找与给定拦截器对应的 {@link ControllerAdviceBean}（拦截器本身标注 @ControllerAdvice 时）。
     */
    protected ControllerAdviceBean findControllerAdviceBean(HandlerInterceptor interceptor) {
        if (webContext == null) {
            return null;
        }
        Class<?> target = AopUtils.getTargetClass(interceptor);
        for (ControllerAdviceBean adviceBean : ControllerAdviceBean.findAnnotatedBeans(webContext.getCtx())) {
            if (adviceBean.getBeanType() != null && adviceBean.getBeanType().equals(target)) {
                return adviceBean;
            }
        }
        return null;
    }

    public boolean preHandle(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        List<HandlerInterceptor> interceptors = getInterceptors(request);
        PathMappingContext mappingContext = PathMappingContext.get(request);
        int passed = 0;
        try {
            for (HandlerInterceptor i : interceptors) {
                if (!i.preHandle(request, response, mappingContext)) {
                    // Spring 语义：preHandle 返回 false 时仅对已通过 preHandle 的拦截器执行
                    // afterCompletion（HandlerExecutionChain.triggerAfterCompletion 只覆盖到
                    // interceptorIndex）。未进入的拦截器未持有资源，不应收到回调；修复前对
                    // 所有拦截器调用会误触发尚未 preHandle 的拦截器的回调。
                    afterCompletionForPassed(request, response, null, interceptors, passed);
                    return false;
                }
                passed++;
            }
        } catch (Exception e) {
            // 对齐 Spring（HandlerExecutionChain.applyPreHandle 抛异常 → doDispatch catch →
            // processDispatchResult → triggerAfterCompletion）：interceptorIndex 覆盖的已通过者
            // 仍应收到一次 afterCompletion(exception)，随后重新抛出交给上层异常处理。
            afterCompletionForPassed(request, response, e, interceptors, passed);
            throw e;
        }
        return true;
    }

    /**
     * 仅对已通过 preHandle 的拦截器逆序调用 afterCompletion（preHandle 提前返回 false 时用）。
     * 正常请求完成路径仍走 {@link #afterCompletion} 全量回调。
     */
    private void afterCompletionForPassed(WebServerHttpRequest request, WebServerHttpResponse response,
                                          Throwable exception, List<HandlerInterceptor> interceptors, int passed) {
        PathMappingContext mappingContext = PathMappingContext.get(request);
        try {
            for (int i = passed - 1; i >= 0; i--) {
                interceptors.get(i).afterCompletion(request, response, mappingContext, exception);
            }
        } catch (Throwable e) {
            log.error("Interceptor afterCompletion failed", e);
        }
    }

    /**
     * Controller 执行后调用（在处理参数前）
     */
    public void postHandle(WebServerHttpRequest request, WebServerHttpResponse response, Object result) {
        List<HandlerInterceptor> interceptors = getInterceptors(request);
        PathMappingContext mappingContext = PathMappingContext.get(request);
        try {
            for (HandlerInterceptor i : interceptors) {
                i.postHandle(request, response, mappingContext, result);
            }
        } catch (Throwable e) {
            log.error("Interceptor postHandle failed", e);
        }

    }

    /**
     * 无论是否异常，都会在请求完成后执行(开启异步时，不会立刻执行，会在异步处理完后执行)
     */
    public void afterCompletion(WebServerHttpRequest request, WebServerHttpResponse response, Throwable exception) {
        List<HandlerInterceptor> interceptors = getInterceptors(request);
        PathMappingContext mappingContext = PathMappingContext.get(request);
        try {
            for (HandlerInterceptor i : interceptors) {
                i.afterCompletion(request, response, mappingContext, exception);
            }
        } catch (Throwable e) {
            log.error("Interceptor afterCompletion failed", e);
        }
    }

    /**
     * 开启异步处理时调用
     */
    public void afterConcurrentHandlingStarted(WebServerHttpRequest request, WebServerHttpResponse response) {
        List<HandlerInterceptor> interceptors = getInterceptors(request);
        PathMappingContext mappingContext = PathMappingContext.get(request);
        try {
            for (HandlerInterceptor i : interceptors) {
                i.afterConcurrentHandlingStarted(request, response, mappingContext);
            }
        } catch (Throwable e) {
            log.error("Interceptor afterConcurrentHandlingStarted failed", e);
        }
    }


    protected List<HandlerInterceptor> getInterceptors(WebServerHttpRequest request) {
        List<HandlerInterceptor> interceptors = request.getRequestContext().getAttribute(INTERCEPTORS_ATTRIBUTE);
        if (interceptors == null) {
            interceptors = realGetInterceptors(request);
            request.getRequestContext().setAttribute(INTERCEPTORS_ATTRIBUTE, interceptors);
        }
        return interceptors;
    }

    /**
     * 获取当前请求对应的拦截器
     *
     * @param request
     * @return
     */
    protected List<HandlerInterceptor> realGetInterceptors(WebServerHttpRequest request) {
        PathMappingContext mappingContext = PathMappingContext.get(request);
        if (mappingContext == null) {
            return runtimeMappingInterceptors;
        }
        List<HandlerInterceptor> interceptors = mappingContext.getCachedInterceptors();
        if (interceptors == null) {
            synchronized (mappingContext) {
                interceptors = mappingContext.getCachedInterceptors();
                if (interceptors == null) {
                    interceptors = initCachedInterceptors(mappingContext);
                    mappingContext.setCachedInterceptors(interceptors);
                }
            }
        }
        return getRuntimeInterceptors(request, interceptors);
    }

    /**
     * 获取mappingContext对应的拦截器（放入方法级缓存）。
     * 类级匹配（@ControllerAdvice）的 registration 按 handler 的 controller 类型判定；
     * 其余按 path 规则判定。
     *
     * @param mappingContext
     * @return
     */
    protected List<HandlerInterceptor> initCachedInterceptors(PathMappingContext mappingContext) {
        String pathRule = mappingContext.getPathRule();
        List<HandlerInterceptor> interceptors = new ArrayList<>();
        for (InterceptorRegistration registration : registrations) {
            if (registration.isControllerAdviceScoped()) {
                if (registration.matchesControllerType(mappingContext.getBeanType())) {
                    interceptors.add(registration.getInterceptor());
                }
                continue;
            }
            ContainmentResult containmentResult = registration.matchPathRuleToCached(pathRule);
            if (containmentResult == ContainmentResult.ALWAYS) {
                interceptors.add(registration.getInterceptor());
            } else if (containmentResult == ContainmentResult.RUNTIME) {
                interceptors.add(getRuntimeMappingInterceptor(registration));
            }
        }
        return interceptors;
    }

    protected HandlerInterceptor getRuntimeMappingInterceptor(InterceptorRegistration registration) {
        String[] include = StringUtils.toStringArray(registration.getIncludePatterns());
        String[] exclude = StringUtils.toStringArray(registration.getExcludePatterns());
        if (include.length == 0 && exclude.length == 0) {
            return registration.getInterceptor();
        }
        if (registration.getPathMatcher() == null) {
            return new RuntimeMappingInterceptor(include, exclude, registration.getInterceptor());
        } else {
            return new RuntimeMappingInterceptor(include, exclude, registration.getInterceptor(), registration.getPathMatcher());
        }
    }

    /**
     * 获取运行时对应的拦截器
     *
     * @param request
     * @param interceptors
     * @return
     */
    protected List<HandlerInterceptor> getRuntimeInterceptors(WebServerHttpRequest request, List<HandlerInterceptor> interceptors) {
        if (interceptors.isEmpty()) {
            return interceptors;
        }
        boolean haveRealTimeMappingInterceptor = false;
        for (HandlerInterceptor i : interceptors) {
            if (i instanceof RuntimeMappingInterceptor) {
                haveRealTimeMappingInterceptor = true;
                break;
            }
        }
        if (haveRealTimeMappingInterceptor) {
            String path = request.getPath();
            List<HandlerInterceptor> runtimeInterceptors = new ArrayList<>();
            for (HandlerInterceptor interceptor : interceptors) {
                if (interceptor instanceof RuntimeMappingInterceptor) {
                    RuntimeMappingInterceptor runtimeMappingInterceptor = (RuntimeMappingInterceptor) interceptor;
                    if (runtimeMappingInterceptor.matches(path)) {
                        runtimeInterceptors.add(runtimeMappingInterceptor.getInterceptor());
                    }
                } else {
                    runtimeInterceptors.add(interceptor);
                }
            }
            return runtimeInterceptors;
        } else {
            return interceptors;
        }
    }
}

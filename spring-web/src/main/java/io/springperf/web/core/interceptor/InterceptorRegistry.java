package io.springperf.web.core.interceptor;

import io.springperf.web.context.WebComponentContainer;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.util.support.ContainmentResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotationAwareOrderUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the registration and lifecycle of HandlerInterceptors, supporting path-pattern-based matching and runtime resolution.
 */
@Slf4j
public class InterceptorRegistry extends WebComponentContainer {

    public static final String INTERCEPTORS_ATTRIBUTE = HandlerInterceptor.class.getSimpleName() + "_KEY";
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
        registrations.stream().map(this::getRuntimeMappingInterceptor).forEach(runtimeMappingInterceptors::add);
    }

    protected InterceptorRegistration registerInterceptor(HandlerInterceptor interceptor) {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);
        registrations.add(registration);
        Integer order = AnnotationAwareOrderUtils.findOrder(interceptor);
        if (order != null) {
            registration.order(order);
        }
        return registration;
    }

    public boolean preHandle(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        List<HandlerInterceptor> interceptors = getInterceptors(request);
        PathMappingContext mappingContext = PathMappingContext.get(request);
        for (HandlerInterceptor i : interceptors) {
            if (!i.preHandle(request, response, mappingContext)) {
                afterCompletion(request, response, null);
                return false;
            }
        }
        return true;
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
            log.error(e.getMessage(), e);
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
            log.error(e.getMessage(), e);
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
            log.error(e.getMessage(), e);
        }
    }


    protected List<HandlerInterceptor> getInterceptors(WebServerHttpRequest request) {
        List<HandlerInterceptor> interceptors = (List<HandlerInterceptor>) request.getRequestContext().getAttribute(INTERCEPTORS_ATTRIBUTE);
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
     * 获取mappingContext对应的拦截器
     *
     * @param mappingContext
     * @return
     */
    protected List<HandlerInterceptor> initCachedInterceptors(PathMappingContext mappingContext) {
        String pathRule = mappingContext.getPathRule();
        List<HandlerInterceptor> interceptors = new ArrayList<>();
        for (InterceptorRegistration registration : registrations) {
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

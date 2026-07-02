package io.springperf.web.core.invoker;

import io.springperf.web.annotation.Optimize;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

public class InvokableHandlerMethod extends HandlerMethod {

    /**
     * GraalVM native-image 检测：运行时动态生成字节码在封闭世界中不可用。
     * 提前检查避免 {@link FastInvokerGenerator} 的异常开销。
     */
    private static final boolean IN_NATIVE_IMAGE =
            System.getProperty("org.graalvm.nativeimage.imagecode") != null;

    protected static ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    private Invoker invoker;

    private final boolean optimized;

    public InvokableHandlerMethod(Object bean, Method method) {
        super(bean, method);
        if (bean instanceof Invoker) {
            this.invoker = (Invoker) bean;
            this.optimized = false;
        } else {
            this.optimized = hasOptimizeAnnotation();
            this.invoker = initInvoker();
        }
        for (MethodParameter methodParameter : getMethodParameters()) {
            methodParameter.initParameterNameDiscovery(parameterNameDiscoverer);
        }
    }

    public InvokableHandlerMethod(HandlerMethod handlerMethod) {
        super(handlerMethod);
        this.optimized = hasOptimizeAnnotation();
        this.invoker = initInvoker();
        for (MethodParameter methodParameter : getMethodParameters()) {
            methodParameter.initParameterNameDiscovery(parameterNameDiscoverer);
        }
    }

    private boolean hasOptimizeAnnotation() {
        return getMethodAndClassAnnotation(Optimize.class) != null;
    }

    private Invoker initInvoker() {
        if (optimized && !IN_NATIVE_IMAGE) {
            try {
                return createFastInvoker();
            } catch (Throwable e) {
                logger.error("create fast invoker error", e);
            }
        }
        return createCommonInvoker();
    }

    public <A extends Annotation> A getMethodAndClassAnnotation(Class<A> annotationType) {
        A methodAnnotation = AnnotatedElementUtils.findMergedAnnotation(getMethod(), annotationType);
        if (methodAnnotation != null) {
            return methodAnnotation;
        }
        return AnnotatedElementUtils.findMergedAnnotation(getBeanType(), annotationType);
    }

    public Object invoke(Object[] args, WebServerHttpRequest request, WebServerHttpResponse response) throws Throwable {
        Object value = invoker.invoke(args);
        setResponseStatus(request, response);
        return value;
    }

    public void setInvoker(Invoker invoker) {
        this.invoker = invoker;
    }

    public Invoker getInvoker() {
        return invoker;
    }

    protected Invoker createFastInvoker() {
        try {
            return FastInvokerGenerator.createInvoker(getBean(), getBeanType(), getBridgedMethod());
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    protected Invoker createCommonInvoker() {
        try {
            MethodHandle methodHandle = MethodHandles.lookup().unreflect(getBridgedMethod()).bindTo(getBean());
            return new MethodHandleInvoker(methodHandle);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public MethodParameter[] createMethodParameters() {
        return getMethodParameters();
    }

    public void setResponseStatus(WebServerHttpRequest request, WebServerHttpResponse response) {
        HttpStatusCode statusCode = getResponseStatus();
        if (statusCode == null) {
            return;
        }
        if (response != null) {
            String reason = getResponseStatusReason();
            if (StringUtils.hasText(reason)) {
                response.sendError(HttpStatus.resolve(statusCode.value()), reason);
            } else {
                response.setStatusCode(statusCode);
            }
        }
    }

    public boolean isOptimize() {
        return optimized;
    }
}

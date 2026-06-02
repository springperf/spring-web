package io.springperf.web.core.invoker;

import io.springperf.web.annotation.Optimize;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

public class InvokableHandlerMethod extends HandlerMethod {

    private final Invoker invoker;

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
    }

    public InvokableHandlerMethod(HandlerMethod handlerMethod) {
        super(handlerMethod);
        this.optimized = hasOptimizeAnnotation();
        this.invoker = initInvoker();
    }

    private boolean hasOptimizeAnnotation() {
        return getMethodAndClassAnnotation(Optimize.class) != null;
    }

    private Invoker initInvoker() {
        if (optimized) {
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

    @Override
    public Method getBridgedMethod() {
        return super.getBridgedMethod();
    }

    public Object invoke(Object[] args, WebServerHttpRequest request, WebServerHttpResponse response) throws Throwable {
        Object value = invoker.invoke(args);
        setResponseStatus(request, response);
        return value;
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
        MethodParameter[] result = new MethodParameter[getBridgedMethod().getParameterCount()];
        for (int i = 0; i < result.length; i++) {
            result[i] = new MethodParameter(getBridgedMethod(), i);
        }
        return result;
    }

    public void setResponseStatus(WebServerHttpRequest request, WebServerHttpResponse response) {
        HttpStatus status = getResponseStatus();
        if (status == null) {
            return;
        }
        if (response != null) {
            String reason = getResponseStatusReason();
            if (StringUtils.hasText(reason)) {
                response.sendError(status, reason);
            } else {
                response.setStatusCode(status);
            }
        }
    }

    public boolean isOptimize() {
        return optimized;
    }

    public Invoker getInvoker() {
        return invoker;
    }
}

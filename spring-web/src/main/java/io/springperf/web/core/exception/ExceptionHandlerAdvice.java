package io.springperf.web.core.exception;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Wraps a {@link org.springframework.web.bind.annotation.ControllerAdvice @ControllerAdvice}
 * bean and its exception handler methods for use within the REST web framework.
 * <p>
 * Resolves the appropriate {@link MappingHandlerMethod} for a given exception type,
 * caching resolved handler methods for efficiency.
 */
public class ExceptionHandlerAdvice extends BaseWebComponent {

    protected final ControllerAdviceBean adviceBean;

    protected final ExceptionHandlerMethodResolver resolver;

    protected Object realBean;

    private Map<Method, MappingHandlerMethod> handlerMethodMap = new HashMap<>();

    public ExceptionHandlerAdvice(Object realBean, ExceptionHandlerMethodResolver resolver) {
        this.adviceBean = null;
        this.resolver = resolver;
        this.realBean = realBean;
    }

    public ExceptionHandlerAdvice(ControllerAdviceBean adviceBean, ExceptionHandlerMethodResolver resolver) {
        this.adviceBean = adviceBean;
        this.resolver = resolver;
    }

    @Override
    public String getComponentName() {
        return getClass().getSimpleName() + ":" + getRealBean().getClass().getName();
    }

    /**
     * Returns whether this advice applies to the specified bean type.
     */
    public boolean isApplicableToBeanType(Class<?> beanType) {
        if (adviceBean == null) {
            return beanType != null && beanType.isAssignableFrom(realBean.getClass());
        }
        return adviceBean.isApplicableToBeanType(beanType);
    }

    /**
     * Resolves and returns the {@link MappingHandlerMethod} for the given exception.
     * <p>Resolved handler methods are cached for subsequent invocations.
     *
     * @param exception the exception to resolve a handler for
     * @return the resolved handler method, or {@code null} if no suitable handler exists
     */
    public MappingHandlerMethod resolveHandlerMethod(Throwable exception) {
        Method method = resolver.resolveMethodByThrowable(exception);
        if (method == null) {
            return null;
        }
        MappingHandlerMethod handlerMethod = handlerMethodMap.get(method);
        if (handlerMethod == null) {
            synchronized (handlerMethodMap) {
                handlerMethod = handlerMethodMap.get(method);
                if (handlerMethod == null) {
                    handlerMethod = new MappingHandlerMethod(getRealBean(), method);
                    handlerMethodMap.put(method, handlerMethod);
                }
            }
        }
        return handlerMethod;
    }

    /**
     * Returns the underlying advice bean instance, resolving it lazily if needed.
     *
     * @return the resolved advice bean
     */
    public Object getRealBean() {
        if (realBean == null) {
            realBean = adviceBean.resolveBean();
        }
        return realBean;
    }

    @Override
    public int getOrder() {
        if (adviceBean == null) {
            return Integer.MIN_VALUE;
        }
        return adviceBean.getOrder();
    }
}

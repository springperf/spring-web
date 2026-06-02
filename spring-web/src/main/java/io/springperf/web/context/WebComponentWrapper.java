package io.springperf.web.context;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderUtils;

public class WebComponentWrapper<T> implements WebComponent {

    private final T component;
    protected String componentName;

    protected Integer order;

    public WebComponentWrapper(T component) {
        this.component = component;
        this.componentName = getComponentClass().getSimpleName();
    }

    public WebComponentWrapper(T component, String componentName) {
        this.component = component;
        this.componentName = componentName;
    }

    public T getComponent() {
        return component;
    }

    public Class<?> getComponentClass() {
        return component.getClass();
    }

    @Override
    public String getComponentName() {
        return componentName;
    }

    public void setComponentName(String componentName) {
        this.componentName = componentName;
    }

    @Override
    public int getOrder() {
        if (order == null) {
            order = AnnotationAwareOrderUtils.findOrder(component);
            if (order == null) {
                order = defaultOrderPriority();
            }
        }
        return order;
    }

    public void setOrder(Integer order) {
        this.order = order;
    }

    protected int defaultOrderPriority() {
        return Ordered.LOWEST_PRECEDENCE - 10000;
    }

    @Override
    public String toString() {
        return "Wrapper: " + componentName + ": " + component.toString();
    }
}

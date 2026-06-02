package io.springperf.web.context;

import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class WebComponentWrapperUtils {

    public static <T> T getComponent(WebComponentContainer componentContainer, Class<T> clazz) {
        List<T> list = getComponents(componentContainer, clazz);
        return list.isEmpty() ? null : list.get(0);
    }

    public static <T> List<T> getComponents(WebComponentContainer componentContainer, Class<T> clazz) {
        List list = new ArrayList<>();
        for (Object component : componentContainer.webComponents.values()) {
            Class<?> componentClass = component.getClass();
            if (component instanceof WebComponentWrapper) {
                WebComponentWrapper componentWrapper = (WebComponentWrapper) component;
                componentClass = componentWrapper.getComponentClass();
            }
            if (clazz.isAssignableFrom(componentClass)) {
                list.add(component);
            }
        }
        AnnotationAwareOrderComparator.sort(list);
        for (int i = 0; i < list.size(); i++) {
            Object component = list.get(i);
            if (component instanceof WebComponentWrapper) {
                WebComponentWrapper componentWrapper = (WebComponentWrapper) component;
                list.set(i, componentWrapper.getComponent());
            }
        }
        return list;
    }

    public static <T> T getComponentWithDefault(WebComponentContainer componentContainer, Class<T> clazz, T defaultComponent) {
        T component = getComponent(componentContainer, clazz);
        if (component == null) {
            registerComponent(componentContainer, clazz);
            component = getComponent(componentContainer, clazz);
            if (component == null && defaultComponent != null) {
                registerComponent(componentContainer, defaultComponent);
                component = defaultComponent;
            }
        }
        return component;
    }

    public static <T> void initRealComponentList(WebComponentContainer componentContainer, List<T> list, Class<T> clazz) {
        list.clear();
        list.addAll(getComponents(componentContainer, clazz));
    }

    public static <T> void registerComponent(WebComponentContainer componentContainer, Class<T> clazz) {
        Map<String, T> beanMap = componentContainer.getWebContext().getCtx().getBeansOfType(clazz);
        for (T bean : beanMap.values()) {
            registerComponent(componentContainer, bean);
        }
    }

    public static <T> void registerComponent(WebComponentContainer componentContainer, T bean) {
        if (bean instanceof WebComponent) {
            componentContainer.registerWebComponent((WebComponent) bean);
        } else {
            WebComponentWrapper<T> componentWrapper = new WebComponentWrapper<>(bean);
            componentContainer.registerWebComponent(componentWrapper);
        }
    }
}

package org.springframework.core.annotation;

public class AnnotationAwareOrderUtils {

    public static Integer findOrder(Object bean) {
        return AnnotationAwareOrderComparator.INSTANCE.findOrder(bean);
    }
}

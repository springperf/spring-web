package org.springframework.core.annotation;

import org.springframework.lang.Nullable;

public class AnnotationAwareOrderUtils {

    @Nullable
    public static Integer findOrder(Object bean) {
        return AnnotationAwareOrderComparator.INSTANCE.findOrder(bean);
    }
}

package org.springframework.core.annotation;

import org.springframework.lang.Nullable;

public class AnnotationAwareOrderUtils {

    /** 委托 Spring 的 OrderComparator：目标无 order 时返回 null（对齐上游 @Nullable 契约）。 */
    @Nullable
    public static Integer findOrder(Object bean) {
        return AnnotationAwareOrderComparator.INSTANCE.findOrder(bean);
    }
}

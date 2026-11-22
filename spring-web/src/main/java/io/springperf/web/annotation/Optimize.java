package io.springperf.web.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 标记需要框架针对性优化的控制器类或方法。
 *
 * @since 1.0.0
 */
@Target({ TYPE, METHOD, ANNOTATION_TYPE })
@Retention(RUNTIME)
public @interface Optimize {

    /**
     * 优化策略名称；空串表示由框架按上下文选择默认策略。
     *
     * @return 优化策略名称
     */
    String value() default "";
}

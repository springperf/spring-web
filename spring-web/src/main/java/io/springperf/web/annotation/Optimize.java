package io.springperf.web.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({TYPE, METHOD,ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface Optimize {

    String value() default "";
}

package io.springperf.web.annotation;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import io.springperf.web.core.async.stream.StreamEmitter;

@Target({ TYPE, METHOD, ANNOTATION_TYPE })
@Retention(RUNTIME)
public @interface ReactiveSupport {

    int highWaterMark() default 150;

    int lowWaterMark() default 50;

    Class<? extends StreamEmitter> streamEmitterType();

    long timeout() default -1;
}

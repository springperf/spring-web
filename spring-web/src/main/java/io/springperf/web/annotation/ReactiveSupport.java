package io.springperf.web.annotation;

import io.springperf.web.core.async.stream.StreamEmitter;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({TYPE, METHOD})
@Retention(RUNTIME)
public @interface ReactiveSupport {

    int highWaterMark() default 150;

    int lowWaterMark() default 50;

    Class<? extends StreamEmitter> streamEmitterType();

    long timeout() default -1;
}

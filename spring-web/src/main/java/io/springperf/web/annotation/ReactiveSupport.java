package io.springperf.web.annotation;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import io.springperf.web.core.async.stream.StreamEmitter;

/**
 * 声明响应式返回值的流式写出策略，供参数/返回值解析阶段识别。
 *
 * @since 1.0.0
 */
@Target({ TYPE, METHOD, ANNOTATION_TYPE })
@Retention(RUNTIME)
public @interface ReactiveSupport {

    /**
     * 背压高水位：在途事件数达到该值时暂停上游请求。
     *
     * @return 高水位阈值
     */
    int highWaterMark() default 150;

    /**
     * 背压低水位：在途事件数回落到该值时恢复上游请求。
     *
     * @return 低水位阈值
     */
    int lowWaterMark() default 50;

    /**
     * 流式写出使用的发射器实现。
     *
     * @return 发射器类型
     */
    Class<? extends StreamEmitter> streamEmitterType();

    /**
     * 单条流的超时时间（毫秒）；负值表示不设超时。
     *
     * @return 超时时间
     */
    long timeout() default -1;
}

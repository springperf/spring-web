package io.springperf.web.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明方法体在 Netty EventLoop 线程上执行；仅适用于不含阻塞操作的短任务。
 *
 * @since 1.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@RunInPool(RunInPool.EVENTLOOP)
public @interface RunInEventloop {
}

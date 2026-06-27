package io.springperf.web.annotation;

import io.springperf.web.core.pool.BizPoolRegistry;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在 HandlerMethod 上，指定该方法应运行在哪个业务线程池中。
 * <p>
 * 默认值 "default" 对应全局配置 {@code pool.*} 创建的线程池。
 * 不标注此注解的方法默认在 {@code default} 业务线程池中执行，
 * 可通过 {@code pool.default-execute-mode=eventloop} 配置为 EventLoop。
 * <p>
 * 使用 {@link #EVENTLOOP} 常量可显式声明在 EventLoop 上执行：
 * <pre>{@code
 * &#64;GetMapping("/fast")
 * &#64;RunInPool(RunInPool.EVENTLOOP)
 * public String fast() { ... }
 * }</pre>
 * <p>
 * 使用自定义池：
 * <pre>{@code
 * &#64;GetMapping("/report")
 * &#64;RunInPool("io")
 * public Report generate() { ... }
 * }</pre>
 *
 * @see BizPoolRegistry
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RunInPool {

    /**
     * 保留关键字，标注此值时强制在 Netty EventLoop 上执行。
     * 对应配置 {@code pool.default-execute-mode=eventloop}。
     */
    String EVENTLOOP = "eventloop";

    /**
     * 线程池名称，对应 {@link BizPoolRegistry} 中注册的池。
     *
     * @return the pool name
     */
    String value() default "default";
}

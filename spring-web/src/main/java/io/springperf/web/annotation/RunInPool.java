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
 * 不标注此注解的方法直接在 Netty EventLoop 中执行（非阻塞处理）。
 * <pre><code>
 * &#64;GetMapping("/report")
 * &#64;RunInPool("io")
 * public Report generate() { ... }
 *
 * &#64;GetMapping("/fast")
 * public String fast() { ... }  // event loop
 * </code></pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RunInPool {

    /**
     * 线程池名称，对应 {@link BizPoolRegistry} 中注册的池。
     *
     * @return the pool name
     */
    String value() default "default";
}

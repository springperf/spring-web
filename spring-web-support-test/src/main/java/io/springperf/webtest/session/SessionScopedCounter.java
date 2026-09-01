package io.springperf.webtest.session;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 {@link SessionScope} 的测试 bean：同一 session 内共享同一实例，
 * 不同 session 各自独立。用于验证 support 模块注册的 {@code session} scope。
 */
@Component
@SessionScope
public class SessionScopedCounter {

    private final AtomicInteger counter = new AtomicInteger();

    public int increment() {
        return counter.incrementAndGet();
    }
}

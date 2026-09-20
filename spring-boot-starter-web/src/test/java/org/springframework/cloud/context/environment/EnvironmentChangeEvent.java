package org.springframework.cloud.context.environment;

import org.springframework.context.ApplicationEvent;

import java.util.Set;

/**
 * 测试桩：模拟 Spring Cloud Context 的 {@code EnvironmentChangeEvent}。
 *
 * <p>生产 classpath 无 Spring Cloud 依赖（框架按类名匹配事件，不编译期引用），
 * 因此测试中放入同名桩类以验证类名匹配逻辑。</p>
 */
public class EnvironmentChangeEvent extends ApplicationEvent {

    private final Set<String> keys;

    public EnvironmentChangeEvent(Object source, Set<String> keys) {
        super(source);
        this.keys = keys;
    }

    public Set<String> getKeys() {
        return keys;
    }
}

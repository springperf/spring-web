package org.springframework.cloud.context.environment;

import org.springframework.context.ApplicationEvent;

import java.util.Set;

/**
 * 测试桩：模拟 Spring Cloud Context 的 {@code EnvironmentChangeEvent}。
 * <p>
 * 框架按类名匹配该事件（不编译期依赖 Spring Cloud），故测试放入同名桩类以端到端验证。
 * </p>
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

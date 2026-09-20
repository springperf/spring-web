package io.springperf.web.autoconfigure;

import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;

import java.util.Set;

import static org.mockito.Mockito.*;

/**
 * {@link SpringWebPropertyRefreshAutoConfiguration}：按类名匹配配置变更事件。
 *
 * <p>测试 classpath 无 Spring Cloud 依赖（框架不编译期引用 Cloud 类型），
 * 用 {@code org.springframework.cloud.context.environment.EnvironmentChangeEvent} 同名桩类
 * 验证匹配逻辑（精确匹配、父类链匹配、非匹配事件忽略）。</p>
 */
class SpringWebPropertyRefreshAutoConfigurationTest {

    private WebContext webContext;
    private ApplicationListener<ApplicationEvent> listener;

    @BeforeEach
    void setUp() {
        webContext = mock(WebContext.class);
        listener = new SpringWebPropertyRefreshAutoConfiguration()
                .perfWebPropertyRefreshListener(webContext);
    }

    @Test
    void environmentChangeEvent_triggersRefresh() {
        listener.onApplicationEvent(new EnvironmentChangeEvent(this, Set.of("some.key")));

        verify(webContext).refreshProperties();
    }

    @Test
    void subclassOfEnvironmentChangeEvent_triggersRefresh() {
        listener.onApplicationEvent(new EnvironmentChangeEventSubclass(this));

        verify(webContext).refreshProperties();
    }

    @Test
    void unrelatedEvent_doesNotRefresh() {
        listener.onApplicationEvent(new UnrelatedEvent(this));

        verify(webContext, never()).refreshProperties();
    }

    /** Spring Cloud 可能的事件子类：父类链匹配应同样生效。 */
    static class EnvironmentChangeEventSubclass extends EnvironmentChangeEvent {
        EnvironmentChangeEventSubclass(Object source) {
            super(source, Set.of());
        }
    }

    static class UnrelatedEvent extends ApplicationEvent {
        UnrelatedEvent(Object source) {
            super(source);
        }
    }
}

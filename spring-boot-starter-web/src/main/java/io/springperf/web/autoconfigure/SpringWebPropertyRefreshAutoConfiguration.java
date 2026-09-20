package io.springperf.web.autoconfigure;

import io.springperf.web.context.WebContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 配置中心动态刷新自动装配：监听 Spring Cloud 的 {@code EnvironmentChangeEvent}
 * 并清空框架级配置缓存。
 *
 * <p><b>为什么不用编译期依赖</b>：本配置类<b>不 import 任何 Spring Cloud 类型</b>，
 * 而是监听泛化的 {@link ApplicationEvent} 并在运行期按类名匹配。因此：</p>
 * <ul>
 *   <li>框架 POM <b>无需引入 spring-cloud-context</b>（连 optional 都不需要），
 *       不污染任何用户的依赖树；</li>
 *   <li>未引入 Spring Cloud 时，事件永远不会出现，监听器恒为空操作（零开销）；</li>
 *   <li>GraalVM native-image 无需为 Cloud 类型注册可达性提示（根本不引用）。</li>
 * </ul>
 *
 * <p>事件类名 {@code org.springframework.cloud.context.environment.EnvironmentChangeEvent}
 * 是 Spring Cloud Context 的稳定公开 API（自 1.0 起未变）。</p>
 *
 * @since 3.5.7
 * @see io.springperf.web.context.PropertyRefreshHandler
 */
@Slf4j
@Configuration
public class SpringWebPropertyRefreshAutoConfiguration {

    /** Spring Cloud Context 的配置变更事件全限定类名（按名匹配，避免编译期依赖）。 */
    private static final String ENVIRONMENT_CHANGE_EVENT =
            "org.springframework.cloud.context.environment.EnvironmentChangeEvent";

    /**
     * 低优先级监听器：仅处理配置变更事件，其余事件直接忽略。
     *
     * <p>使用 {@link ApplicationListener} 而非 {@code @EventListener}，避免依赖
     * 注解驱动的额外条件；注册时机由 Spring 容器保证在 {@link WebContext} 就绪之后。</p>
     */
    @Bean
    public ApplicationListener<ApplicationEvent> perfWebPropertyRefreshListener(WebContext webContext) {
        return event -> {
            if (!isEnvironmentChangeEvent(event)) {
                return;
            }
            webContext.refreshProperties();
            log.info("Perf-web property caches cleared on Spring Cloud EnvironmentChangeEvent");
        };
    }

    /**
     * 按类名（含父类链）判断是否为配置变更事件，兼容 Spring Cloud 可能的事件子类。
     */
    private static boolean isEnvironmentChangeEvent(ApplicationEvent event) {
        for (Class<?> type = event.getClass(); type != null; type = type.getSuperclass()) {
            if (ENVIRONMENT_CHANGE_EVENT.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }
}

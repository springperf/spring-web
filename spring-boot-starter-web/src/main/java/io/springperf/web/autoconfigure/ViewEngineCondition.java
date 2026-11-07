package io.springperf.web.autoconfigure;

import io.springperf.web.view.ViewProperties;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Set;

/**
 * 判断某个视图引擎是否启用。逻辑：
 * <ul>
 * <li>未配置 {@code spring.web.view.engine} → 放行（由 classpath 探测决定是否可用）</li>
 * <li>配置为多值列表（逗号分隔）→ 列表包含当前引擎名则放行</li>
 * <li>否则禁用</li>
 * </ul>
 * 取值合法性与 {@code none} 独占约束由 {@link ViewProperties#parseEngines(String)} 统一校验，
 * 非法配置在装配期即中断启动，不会拖到首次视图请求才 500。
 */
public class ViewEngineCondition implements Condition {

    private final String engineName;

    protected ViewEngineCondition(String engineName) {
        this.engineName = engineName;
    }

    public static class Thymeleaf extends ViewEngineCondition {
        public Thymeleaf() {
            super("thymeleaf");
        }
    }

    public static class FreeMarker extends ViewEngineCondition {
        public FreeMarker() {
            super("freemarker");
        }
    }

    public static class Beetl extends ViewEngineCondition {
        public Beetl() {
            super("beetl");
        }
    }

    /**
     * JSP 不属于多选引擎列表：除 {@code engine=none} 外一律放行（含显式指定了其他引擎的情形）。
     * 自定义 {@code ViewResolver} Bean 由用户自行装配，不受此条件约束。
     */
    public static class Jsp implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !ViewProperties.parseEngines(context.getEnvironment().getProperty(ViewProperties.ENGINE))
                    .contains(ViewProperties.ENGINE_NONE);
        }
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Set<String> engines = ViewProperties.parseEngines(
                context.getEnvironment().getProperty(ViewProperties.ENGINE));
        return engines.isEmpty() || engines.contains(engineName);
    }
}

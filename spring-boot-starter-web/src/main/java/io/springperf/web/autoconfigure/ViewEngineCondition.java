package io.springperf.web.autoconfigure;

import io.springperf.web.view.ViewProperties;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * 判断某个视图引擎是否启用。逻辑：
 * <ul>
 *   <li>未配置 {@code spring.web.view.engine} → 放行（由 classpath 探测决定是否可用）</li>
 *   <li>配置为多值列表（逗号分隔）→ 列表包含当前引擎名则放行</li>
 *   <li>否则禁用</li>
 * </ul>
 */
public class ViewEngineCondition implements Condition {

    private final String engineName;

    protected ViewEngineCondition(String engineName) {
        this.engineName = engineName;
    }

    public static class Thymeleaf extends ViewEngineCondition {
        public Thymeleaf() { super("thymeleaf"); }
    }

    public static class FreeMarker extends ViewEngineCondition {
        public FreeMarker() { super("freemarker"); }
    }

    public static class Beetl extends ViewEngineCondition {
        public Beetl() { super("beetl"); }
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String configured = context.getEnvironment().getProperty(ViewProperties.ENGINE);
        if (!StringUtils.hasText(configured)) {
            return true;
        }
        for (String e : configured.split(",")) {
            if (e.trim().equalsIgnoreCase(engineName)) {
                return true;
            }
        }
        return false;
    }
}
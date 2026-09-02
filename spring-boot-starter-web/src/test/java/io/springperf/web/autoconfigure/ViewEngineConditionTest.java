package io.springperf.web.autoconfigure;

import io.springperf.web.view.ViewProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ViewEngineConditionTest {

    private ConditionContext ctx(String engine) {
        ConditionContext context = mock(ConditionContext.class);
        MockEnvironment env = new MockEnvironment();
        if (engine != null) {
            env.setProperty(ViewProperties.ENGINE, engine);
        }
        when(context.getEnvironment()).thenReturn(env);
        return context;
    }

    @Test
    void notConfigured_matches() {
        assertTrue(new ViewEngineCondition.Thymeleaf().matches(ctx(null), null));
        assertTrue(new ViewEngineCondition.FreeMarker().matches(ctx(null), null));
        assertTrue(new ViewEngineCondition.Beetl().matches(ctx(null), null));
    }

    @Test
    void configuredWithEngine_matches() {
        assertTrue(new ViewEngineCondition.Thymeleaf().matches(ctx("thymeleaf"), null));
        assertTrue(new ViewEngineCondition.FreeMarker().matches(ctx("freemarker"), null));
        assertTrue(new ViewEngineCondition.Beetl().matches(ctx("beetl"), null));
    }

    @Test
    void configuredWithOtherEngine_notMatch() {
        assertFalse(new ViewEngineCondition.Thymeleaf().matches(ctx("freemarker"), null));
        assertFalse(new ViewEngineCondition.FreeMarker().matches(ctx("beetl"), null));
    }

    @Test
    void configuredWithMultiValueList_matchesIfContains() {
        assertTrue(new ViewEngineCondition.Thymeleaf().matches(ctx("none,thymeleaf"), null));
        assertTrue(new ViewEngineCondition.FreeMarker().matches(ctx("thymeleaf, freemarker"), null));
        assertFalse(new ViewEngineCondition.Beetl().matches(ctx("thymeleaf,freemarker"), null));
    }

    @Test
    void caseInsensitiveMatch() {
        assertTrue(new ViewEngineCondition.Thymeleaf().matches(ctx("THYMELEAF"), null));
        assertTrue(new ViewEngineCondition.FreeMarker().matches(ctx("Freemarker"), null));
    }
}

package io.springperf.web.autoconfigure;

import io.springperf.web.view.ViewProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.mock.env.MockEnvironment;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertTrue(new ViewEngineCondition.Thymeleaf().matches(ctx("beetl,thymeleaf"), null));
        assertTrue(new ViewEngineCondition.FreeMarker().matches(ctx("thymeleaf, freemarker"), null));
        assertFalse(new ViewEngineCondition.Beetl().matches(ctx("thymeleaf,freemarker"), null));
    }

    @Test
    void caseInsensitiveMatch() {
        assertTrue(new ViewEngineCondition.Thymeleaf().matches(ctx("THYMELEAF"), null));
        assertTrue(new ViewEngineCondition.FreeMarker().matches(ctx("Freemarker"), null));
    }

    @Test
    void none_disablesAllBuiltinEnginesAndJsp() {
        assertFalse(new ViewEngineCondition.Thymeleaf().matches(ctx("none"), null));
        assertFalse(new ViewEngineCondition.FreeMarker().matches(ctx("none"), null));
        assertFalse(new ViewEngineCondition.Beetl().matches(ctx("none"), null));
        assertFalse(new ViewEngineCondition.Jsp().matches(ctx("none"), null));
    }

    @Test
    void jspCondition_notFilteredByEngineList() {
        assertTrue(new ViewEngineCondition.Jsp().matches(ctx(null), null));
        assertTrue(new ViewEngineCondition.Jsp().matches(ctx("thymeleaf"), null));
    }

    @Test
    void invalidEngine_failsFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ViewEngineCondition.Thymeleaf().matches(ctx("thymeleaf,typo"), null));
        assertTrue(ex.getMessage().contains(ViewProperties.ENGINE));
        assertTrue(ex.getMessage().contains("typo"));
    }

    @Test
    void noneMixedWithEngine_failsFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ViewEngineCondition.Thymeleaf().matches(ctx("none,thymeleaf"), null));
        assertTrue(ex.getMessage().contains("must be used alone"));
    }

    @Test
    void parseEngines_normalizesAndDeduplicates() {
        assertTrue(ViewProperties.parseEngines("").isEmpty());
        assertTrue(ViewProperties.parseEngines(null).isEmpty());
        assertEquals(Set.of("thymeleaf"), ViewProperties.parseEngines("THYMELEAF, thymeleaf"));
        assertThrows(IllegalStateException.class, () -> ViewProperties.parseEngines(","));
    }
}

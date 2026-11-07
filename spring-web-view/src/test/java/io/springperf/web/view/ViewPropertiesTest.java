package io.springperf.web.view;

import io.springperf.web.context.ApplicationProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ViewPropertiesTest {

    @Test
    void resolveBoolean_unconfigured_returnsDefault() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        assertTrue(ViewProperties.resolveBoolean(props, "flag", true));
        assertFalse(ViewProperties.resolveBoolean(props, "flag", false));
    }

    @Test
    void resolveBoolean_acceptsTrueFalseIgnoringCaseAndWhitespace() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get("flag", null)).thenReturn(" TRUE ");
        assertTrue(ViewProperties.resolveBoolean(props, "flag", false));
        when(props.get("flag", null)).thenReturn("False");
        assertFalse(ViewProperties.resolveBoolean(props, "flag", true));
    }

    @Test
    void resolveBoolean_blankValue_returnsDefault() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get("flag", null)).thenReturn("  ");
        assertTrue(ViewProperties.resolveBoolean(props, "flag", true));
    }

    @Test
    void resolveBoolean_invalidValue_failsFast() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get("flag", null)).thenReturn("yes");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ViewProperties.resolveBoolean(props, "flag", true));
        assertTrue(ex.getMessage().contains("flag"));
        assertTrue(ex.getMessage().contains("yes"));
    }

    @Test
    void withSuffix_appendsOnlyWhenMissing() {
        assertEquals("hello.ftl", ViewProperties.withSuffix("hello", ".ftl"));
        assertEquals("hello.ftl", ViewProperties.withSuffix("hello.ftl", ".ftl"));
        assertEquals("hello", ViewProperties.withSuffix("hello", ""));
    }

    @Test
    void withoutSuffix_stripsOnlyWhenPresent() {
        assertEquals("hello", ViewProperties.withoutSuffix("hello.html", ".html"));
        assertEquals("hello", ViewProperties.withoutSuffix("hello", ".html"));
        assertEquals("hello.html", ViewProperties.withoutSuffix("hello.html", ""));
    }

    @Test
    void parseEngines_unknownValue_failsFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ViewProperties.parseEngines("thymeleaf,typo"));
        assertTrue(ex.getMessage().contains(ViewProperties.ENGINE));
    }
}

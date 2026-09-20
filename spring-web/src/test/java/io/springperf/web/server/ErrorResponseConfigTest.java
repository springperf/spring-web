package io.springperf.web.server;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ErrorResponseConfigTest {

    @Test
    void defaults_neverAndWhitelabelEnabled() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.ERROR_INCLUDE_STACKTRACE, PropertiesConstant.ERROR_INCLUDE_STACKTRACE_DEFAULT))
                .thenReturn("never");
        when(props.get(PropertiesConstant.ERROR_INCLUDE_MESSAGE, PropertiesConstant.ERROR_INCLUDE_MESSAGE_DEFAULT))
                .thenReturn("never");
        when(props.get(PropertiesConstant.ERROR_INCLUDE_BINDING_ERRORS, PropertiesConstant.ERROR_INCLUDE_BINDING_ERRORS_DEFAULT))
                .thenReturn("never");
        when(props.getBoolean(PropertiesConstant.ERROR_WHITELABEL_ENABLED, PropertiesConstant.ERROR_WHITELABEL_ENABLED_DEFAULT))
                .thenReturn(true);
        when(props.get(PropertiesConstant.ERROR_PATH, PropertiesConstant.ERROR_PATH_DEFAULT)).thenReturn("/error");

        ErrorResponseConfig cfg = ErrorResponseConfig.fromProperties(props);
        assertEquals(ErrorResponseConfig.IncludePolicy.NEVER, cfg.getIncludeStacktrace());
        assertEquals(ErrorResponseConfig.IncludePolicy.NEVER, cfg.getIncludeMessage());
        assertEquals(ErrorResponseConfig.IncludePolicy.NEVER, cfg.getIncludeBindingErrors());
        assertTrue(cfg.isWhitelabelEnabled());
        assertEquals("/error", cfg.getErrorPath());
    }

    @Test
    void parsePolicies_caseInsensitive() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.ERROR_INCLUDE_STACKTRACE, PropertiesConstant.ERROR_INCLUDE_STACKTRACE_DEFAULT))
                .thenReturn("ALWAYS");
        when(props.get(PropertiesConstant.ERROR_INCLUDE_MESSAGE, PropertiesConstant.ERROR_INCLUDE_MESSAGE_DEFAULT))
                .thenReturn("on-Param");
        when(props.get(PropertiesConstant.ERROR_INCLUDE_BINDING_ERRORS, PropertiesConstant.ERROR_INCLUDE_BINDING_ERRORS_DEFAULT))
                .thenReturn("Always");
        when(props.getBoolean(PropertiesConstant.ERROR_WHITELABEL_ENABLED, PropertiesConstant.ERROR_WHITELABEL_ENABLED_DEFAULT))
                .thenReturn(false);
        when(props.get(PropertiesConstant.ERROR_PATH, PropertiesConstant.ERROR_PATH_DEFAULT)).thenReturn("/err");

        ErrorResponseConfig cfg = ErrorResponseConfig.fromProperties(props);
        assertEquals(ErrorResponseConfig.IncludePolicy.ALWAYS, cfg.getIncludeStacktrace());
        assertEquals(ErrorResponseConfig.IncludePolicy.ON_PARAM, cfg.getIncludeMessage());
        assertEquals(ErrorResponseConfig.IncludePolicy.ALWAYS, cfg.getIncludeBindingErrors());
        assertFalse(cfg.isWhitelabelEnabled());
    }

    @Test
    void parsePolicies_invalidFallsBackToNever() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.ERROR_INCLUDE_STACKTRACE, PropertiesConstant.ERROR_INCLUDE_STACKTRACE_DEFAULT))
                .thenReturn("bogus");
        when(props.get(PropertiesConstant.ERROR_INCLUDE_MESSAGE, PropertiesConstant.ERROR_INCLUDE_MESSAGE_DEFAULT))
                .thenReturn(null);
        when(props.get(PropertiesConstant.ERROR_INCLUDE_BINDING_ERRORS, PropertiesConstant.ERROR_INCLUDE_BINDING_ERRORS_DEFAULT))
                .thenReturn("  ");
        when(props.getBoolean(PropertiesConstant.ERROR_WHITELABEL_ENABLED, PropertiesConstant.ERROR_WHITELABEL_ENABLED_DEFAULT))
                .thenReturn(true);
        when(props.get(PropertiesConstant.ERROR_PATH, PropertiesConstant.ERROR_PATH_DEFAULT)).thenReturn("/error");

        ErrorResponseConfig cfg = ErrorResponseConfig.fromProperties(props);
        assertEquals(ErrorResponseConfig.IncludePolicy.NEVER, cfg.getIncludeStacktrace());
        assertEquals(ErrorResponseConfig.IncludePolicy.NEVER, cfg.getIncludeMessage());
        assertEquals(ErrorResponseConfig.IncludePolicy.NEVER, cfg.getIncludeBindingErrors());
    }

    @Test
    void includeStacktrace_andMessage_policies() {
        ErrorResponseConfig always = new ErrorResponseConfig(
                ErrorResponseConfig.IncludePolicy.ALWAYS, ErrorResponseConfig.IncludePolicy.ALWAYS,
                ErrorResponseConfig.IncludePolicy.NEVER, true, "/error");
        assertTrue(always.includeStacktrace(false));
        assertTrue(always.includeMessage(false));

        ErrorResponseConfig onParam = new ErrorResponseConfig(
                ErrorResponseConfig.IncludePolicy.ON_PARAM, ErrorResponseConfig.IncludePolicy.ON_PARAM,
                ErrorResponseConfig.IncludePolicy.NEVER, true, "/error");
        assertFalse(onParam.includeStacktrace(false));
        assertTrue(onParam.includeStacktrace(true));
        assertFalse(onParam.includeMessage(false));
        assertTrue(onParam.includeMessage(true));

        ErrorResponseConfig never = ErrorResponseConfig.DEFAULT;
        assertFalse(never.includeStacktrace(true));
        assertFalse(never.includeMessage(true));
    }

    @Test
    void includeBindingErrors_onParamGatedByErrorsParam_alwaysIgnoresParam() {
        ErrorResponseConfig onParam = new ErrorResponseConfig(
                ErrorResponseConfig.IncludePolicy.NEVER, ErrorResponseConfig.IncludePolicy.NEVER,
                ErrorResponseConfig.IncludePolicy.ON_PARAM, true, "/error");
        // on-param：只有命中 errors 参数才暴露（对齐 Boot，避免配置「按需」却总是暴露）
        assertFalse(onParam.includeBindingErrors(false));
        assertTrue(onParam.includeBindingErrors(true));

        ErrorResponseConfig never = ErrorResponseConfig.DEFAULT;
        assertFalse(never.includeBindingErrors(true));

        ErrorResponseConfig always = new ErrorResponseConfig(
                ErrorResponseConfig.IncludePolicy.NEVER, ErrorResponseConfig.IncludePolicy.NEVER,
                ErrorResponseConfig.IncludePolicy.ALWAYS, true, "/error");
        assertTrue(always.includeBindingErrors(false));
    }

    // ==================== spring.mvc.problemdetails.enabled ====================

    @Test
    void problemDetails_defaultDisabled() {
        ErrorResponseConfig cfg = ErrorResponseConfig.DEFAULT;
        assertFalse(cfg.isProblemDetailsEnabled());
    }

    @Test
    void problemDetails_legacy5ArgConstructor_defaultsDisabled() {
        ErrorResponseConfig cfg = new ErrorResponseConfig(
                ErrorResponseConfig.IncludePolicy.NEVER, ErrorResponseConfig.IncludePolicy.NEVER,
                ErrorResponseConfig.IncludePolicy.NEVER, true, "/error");
        assertFalse(cfg.isProblemDetailsEnabled());
    }

    @Test
    void problemDetails_enabledFromProperties() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.ERROR_INCLUDE_STACKTRACE, PropertiesConstant.ERROR_INCLUDE_STACKTRACE_DEFAULT))
                .thenReturn("never");
        when(props.get(PropertiesConstant.ERROR_INCLUDE_MESSAGE, PropertiesConstant.ERROR_INCLUDE_MESSAGE_DEFAULT))
                .thenReturn("never");
        when(props.get(PropertiesConstant.ERROR_INCLUDE_BINDING_ERRORS, PropertiesConstant.ERROR_INCLUDE_BINDING_ERRORS_DEFAULT))
                .thenReturn("never");
        when(props.getBoolean(PropertiesConstant.ERROR_WHITELABEL_ENABLED, PropertiesConstant.ERROR_WHITELABEL_ENABLED_DEFAULT))
                .thenReturn(true);
        when(props.get(PropertiesConstant.ERROR_PATH, PropertiesConstant.ERROR_PATH_DEFAULT)).thenReturn("/error");
        when(props.getBoolean(PropertiesConstant.MVC_PROBLEM_DETAILS_ENABLED,
                PropertiesConstant.MVC_PROBLEM_DETAILS_ENABLED_DEFAULT)).thenReturn(true);

        ErrorResponseConfig cfg = ErrorResponseConfig.fromProperties(props);
        assertTrue(cfg.isProblemDetailsEnabled());
    }
}

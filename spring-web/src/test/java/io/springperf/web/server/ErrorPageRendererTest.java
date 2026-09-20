package io.springperf.web.server;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;

import static org.junit.jupiter.api.Assertions.*;

class ErrorPageRendererTest {

    private static ErrorResponseConfig cfg(boolean whitelabel,
                                           ErrorResponseConfig.IncludePolicy stacktrace,
                                           ErrorResponseConfig.IncludePolicy message,
                                           ErrorResponseConfig.IncludePolicy binding) {
        return new ErrorResponseConfig(stacktrace, message, binding, whitelabel, "/error");
    }

    @Test
    void whitelabelHtml_defaultHidesMessageAndTrace() {
        ErrorResponseConfig c = cfg(true, ErrorResponseConfig.IncludePolicy.NEVER,
                ErrorResponseConfig.IncludePolicy.NEVER, ErrorResponseConfig.IncludePolicy.NEVER);
        ErrorPageRenderer.ErrorResponseBody body =
                ErrorPageRenderer.build(HttpStatus.INTERNAL_SERVER_ERROR, "boom", new RuntimeException("boom"), c, false, false);

        assertTrue(body.getContentType().startsWith("text/html"));
        assertTrue(body.getBody().contains("500"));
        assertFalse(body.getBody().contains("boom"));
        assertFalse(body.getBody().contains("RuntimeException"));
    }

    @Test
    void whitelabelHtml_always_showsMessageAndTrace() {
        ErrorResponseConfig c = cfg(true, ErrorResponseConfig.IncludePolicy.ALWAYS,
                ErrorResponseConfig.IncludePolicy.ALWAYS, ErrorResponseConfig.IncludePolicy.NEVER);
        RuntimeException ex = new RuntimeException("kaboom");
        ErrorPageRenderer.ErrorResponseBody body =
                ErrorPageRenderer.build(HttpStatus.BAD_REQUEST, "kaboom", ex, c, false, false);

        assertTrue(body.getBody().contains("kaboom"));
        assertTrue(body.getBody().contains("RuntimeException"));
        // HTML 转义：尖括号不应原样出现
        assertFalse(body.getBody().contains("<script>"));
    }

    @Test
    void whitelabelHtml_onParam_controlledByFlag() {
        ErrorResponseConfig c = cfg(true, ErrorResponseConfig.IncludePolicy.ON_PARAM,
                ErrorResponseConfig.IncludePolicy.ON_PARAM, ErrorResponseConfig.IncludePolicy.NEVER);
        ErrorPageRenderer.ErrorResponseBody hidden =
                ErrorPageRenderer.build(HttpStatus.BAD_REQUEST, "msg-onparam", new RuntimeException("ex"), c, false, false);
        assertFalse(hidden.getBody().contains("msg-onparam"));

        ErrorPageRenderer.ErrorResponseBody shown =
                ErrorPageRenderer.build(HttpStatus.BAD_REQUEST, "msg-onparam", new RuntimeException("ex"), c, true, true);
        assertTrue(shown.getBody().contains("msg-onparam"));
    }

    @Test
    void jsonMode_hidesMessageButKeepsStatus() {
        ErrorResponseConfig c = cfg(false, ErrorResponseConfig.IncludePolicy.NEVER,
                ErrorResponseConfig.IncludePolicy.NEVER, ErrorResponseConfig.IncludePolicy.NEVER);
        ErrorPageRenderer.ErrorResponseBody body =
                ErrorPageRenderer.build(HttpStatus.NOT_FOUND, "secret", null, c, false, false);

        assertTrue(body.getContentType().startsWith("application/json"));
        assertTrue(body.getBody().contains("\"status\":404"));
        assertFalse(body.getBody().contains("secret"));
        assertFalse(body.getBody().contains("\"trace\""));
    }

    @Test
    void jsonMode_always_showsMessageAndEscapes() {
        ErrorResponseConfig c = cfg(false, ErrorResponseConfig.IncludePolicy.NEVER,
                ErrorResponseConfig.IncludePolicy.ALWAYS, ErrorResponseConfig.IncludePolicy.NEVER);
        ErrorPageRenderer.ErrorResponseBody body =
                ErrorPageRenderer.build(HttpStatus.BAD_REQUEST, "a\"b\\c\nd", null, c, false, false);

        String json = body.getBody();
        assertTrue(json.contains("a\\\"b\\\\c\\nd"));
    }

    @Test
    void bindingErrors_includedWhenPolicyAllows() {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "target");
        result.addError(new FieldError("target", "name", "must not be blank"));
        BindException ex = new BindException(result);

        ErrorResponseConfig c = cfg(false, ErrorResponseConfig.IncludePolicy.NEVER,
                ErrorResponseConfig.IncludePolicy.NEVER, ErrorResponseConfig.IncludePolicy.ALWAYS);
        ErrorPageRenderer.ErrorResponseBody body =
                ErrorPageRenderer.build(HttpStatus.BAD_REQUEST, "validation failed", ex, c, false, false);

        assertTrue(body.getBody().contains("\"errors\""));
        assertTrue(body.getBody().contains("must not be blank"));
        assertTrue(body.getBody().contains("name"));
    }

    @Test
    void bindingErrors_hiddenByDefault() {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "target");
        result.addError(new FieldError("target", "name", "must not be blank"));
        BindException ex = new BindException(result);

        ErrorPageRenderer.ErrorResponseBody body =
                ErrorPageRenderer.build(HttpStatus.BAD_REQUEST, "validation failed", ex,
                        ErrorResponseConfig.DEFAULT, false, false);

        assertFalse(body.getBody().contains("must not be blank"));
    }

    @Test
    void htmlEscaping_preventsInjection() {
        ErrorResponseConfig c = cfg(true, ErrorResponseConfig.IncludePolicy.NEVER,
                ErrorResponseConfig.IncludePolicy.ALWAYS, ErrorResponseConfig.IncludePolicy.NEVER);
        ErrorPageRenderer.ErrorResponseBody body =
                ErrorPageRenderer.build(HttpStatus.BAD_REQUEST, "<script>alert(1)</script>", null, c, false, false);

        assertFalse(body.getBody().contains("<script>"));
        assertTrue(body.getBody().contains("&lt;script&gt;"));
    }

    // ==================== RFC 7807 problem+json ====================

    private static ErrorResponseConfig problemCfg(boolean whitelabel,
                                                  ErrorResponseConfig.IncludePolicy stacktrace,
                                                  ErrorResponseConfig.IncludePolicy message,
                                                  ErrorResponseConfig.IncludePolicy binding) {
        return new ErrorResponseConfig(stacktrace, message, binding, whitelabel, "/error", true);
    }

    @Test
    void problemDetails_emitsRfc7807Fields() {
        ErrorResponseConfig c = problemCfg(true, ErrorResponseConfig.IncludePolicy.NEVER,
                ErrorResponseConfig.IncludePolicy.NEVER, ErrorResponseConfig.IncludePolicy.NEVER);
        ErrorPageRenderer.ErrorResponseBody body =
                ErrorPageRenderer.build(HttpStatus.NOT_FOUND, "secret", null, c, false, false);

        assertEquals("application/problem+json;charset=UTF-8", body.getContentType());
        assertTrue(body.getBody().contains("\"type\":\"about:blank\""));
        assertTrue(body.getBody().contains("\"status\":404"));
        assertTrue(body.getBody().contains("\"instance\":\"/error\""));
        // message 策略 never：detail 不出现
        assertFalse(body.getBody().contains("secret"));
        assertFalse(body.getBody().contains("\"detail\""));
    }

    @Test
    void problemDetails_takesPrecedenceOverWhitelabel() {
        // problemdetails 开启时，即使 whitelabel 也开启，仍输出 problem+json
        ErrorResponseConfig c = problemCfg(true, ErrorResponseConfig.IncludePolicy.NEVER,
                ErrorResponseConfig.IncludePolicy.NEVER, ErrorResponseConfig.IncludePolicy.NEVER);
        ErrorPageRenderer.ErrorResponseBody body =
                ErrorPageRenderer.build(HttpStatus.BAD_REQUEST, "x", null, c, false, false);

        assertTrue(body.getContentType().startsWith("application/problem+json"));
        assertFalse(body.getContentType().startsWith("text/html"));
    }

    @Test
    void problemDetails_always_showsDetailAndTrace() {
        ErrorResponseConfig c = problemCfg(false, ErrorResponseConfig.IncludePolicy.ALWAYS,
                ErrorResponseConfig.IncludePolicy.ALWAYS, ErrorResponseConfig.IncludePolicy.NEVER);
        ErrorPageRenderer.ErrorResponseBody body =
                ErrorPageRenderer.build(HttpStatus.INTERNAL_SERVER_ERROR, "kaboom",
                        new RuntimeException("kaboom"), c, false, false);

        assertTrue(body.getBody().contains("\"detail\":\"kaboom\""));
        assertTrue(body.getBody().contains("\"trace\""));
    }

    @Test
    void problemDetails_includesBindingErrorsWhenAllowed() {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "target");
        result.addError(new FieldError("target", "name", "must not be blank"));
        BindException ex = new BindException(result);

        ErrorResponseConfig c = problemCfg(false, ErrorResponseConfig.IncludePolicy.NEVER,
                ErrorResponseConfig.IncludePolicy.NEVER, ErrorResponseConfig.IncludePolicy.ALWAYS);
        ErrorPageRenderer.ErrorResponseBody body =
                ErrorPageRenderer.build(HttpStatus.BAD_REQUEST, "validation failed", ex, c, false, false);

        assertTrue(body.getBody().contains("\"errors\""));
        assertTrue(body.getBody().contains("must not be blank"));
    }
}

package io.springperf.web.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import io.springperf.web.server.ErrorResponseConfig.IncludePolicy;

/** 错误响应体的转义、Accept 协商、绑定错误提取与 problem+json 可选字段矩阵。 */
class ErrorPageRendererEscapingTest {

    private static ErrorResponseConfig jsonConfig(IncludePolicy message, IncludePolicy binding) {
        return new ErrorResponseConfig(IncludePolicy.NEVER, message, binding, false, "/error", false);
    }

    private static String jsonBody(String message, Throwable cause, IncludePolicy binding) {
        return ErrorPageRenderer
                .build(HttpStatus.BAD_REQUEST, message, cause, jsonConfig(IncludePolicy.ALWAYS, binding), false, false)
                .getBody();
    }

    private static BindException bindException(String field, String error) {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "target");
        result.addError(new FieldError("target", field, error));
        return new BindException(result);
    }

    @Test
    void jsonString_escapesNamedControlCharacters() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("\"", "\\\"");
        cases.put("\\", "\\\\");
        cases.put("\b", "\\b");
        cases.put("\f", "\\f");
        cases.put("\n", "\\n");
        cases.put("\r", "\\r");
        cases.put("\t", "\\t");

        for (Map.Entry<String, String> entry : cases.entrySet()) {
            String body = jsonBody(entry.getKey(), null, IncludePolicy.NEVER);
            assertTrue(body.contains("\"message\":\"" + entry.getValue() + "\""),
                    "控制字符应转义为 " + entry.getValue());
        }
    }

    @Test
    void jsonString_escapesOtherControlCharactersAsUnicode() {
        for (int c : new int[] { 0x00, 0x01, 0x1f }) {
            String expected = String.format("\\u%04x", c);
            assertTrue(jsonBody(String.valueOf((char) c), null, IncludePolicy.NEVER).contains(expected),
                    "0x" + Integer.toHexString(c) + " 应转义为 " + expected);
        }
    }

    @Test
    void escapeHtml_escapesAllFiveSpecialCharacters() {
        ErrorResponseConfig cfg = new ErrorResponseConfig(IncludePolicy.NEVER, IncludePolicy.ALWAYS,
                IncludePolicy.NEVER, true, "/error", false);
        String html = ErrorPageRenderer.build(HttpStatus.BAD_REQUEST, "&<>\"'", null, cfg, false, false).getBody();

        assertTrue(html.contains("<p>Message: &amp;&lt;&gt;&quot;&#x27;</p>"));
    }

    @Test
    void escapeHtml_nullMessageOmitsMessageParagraph() {
        ErrorResponseConfig cfg = new ErrorResponseConfig(IncludePolicy.NEVER, IncludePolicy.ALWAYS,
                IncludePolicy.NEVER, true, "/error", false);
        String html = ErrorPageRenderer.build(HttpStatus.BAD_REQUEST, null, null, cfg, false, false).getBody();

        assertFalse(html.contains("Message:"));
        assertTrue(html.contains("<p>Path: /error</p>"));
    }

    @Test
    void statusNull_fallsBackToInternalServerError() {
        ErrorPageRenderer.ErrorResponseBody body = ErrorPageRenderer.build(null, "boom", null,
                jsonConfig(IncludePolicy.ALWAYS, IncludePolicy.NEVER), false, false, "application/json");

        assertTrue(body.getBody().contains("\"status\":500"));
        assertTrue(body.getBody().contains("500 Internal Server Error"));
    }

    @Test
    void acceptsHtml_negotiatesContentType() {
        ErrorResponseConfig cfg = new ErrorResponseConfig(IncludePolicy.NEVER, IncludePolicy.NEVER,
                IncludePolicy.NEVER, true, "/error", false);

        for (String accept : new String[] { null, "", "   ", "*/*", "text/html", "TEXT/HTML" }) {
            ErrorPageRenderer.ErrorResponseBody body = ErrorPageRenderer.build(HttpStatus.NOT_FOUND, "m", null, cfg,
                    false, false, accept);
            assertTrue(body.getContentType().startsWith("text/html"), "Accept=" + accept + " 应返回 whitelabel HTML");
        }
        for (String accept : new String[] { "application/json", "application/problem+json" }) {
            ErrorPageRenderer.ErrorResponseBody body = ErrorPageRenderer.build(HttpStatus.NOT_FOUND, "m", null, cfg,
                    false, false, accept);
            assertTrue(body.getContentType().startsWith("application/json"),
                    "Accept=" + accept + " 应返回 JSON");
        }
    }

    @Test
    void extractBindingErrors_bindException_exposesFieldErrors() {
        String body = jsonBody("validation failed", bindException("name", "must not be blank"), IncludePolicy.ALWAYS);

        assertTrue(body.contains("\"errors\":"));
        assertTrue(body.contains("\"name\":\"must not be blank\""));
    }

    @Test
    void extractBindingErrors_methodArgumentNotValidException_exposesFieldErrors() throws Exception {
        MethodParameter parameter = new MethodParameter(Sample.class.getDeclaredMethod("handle", String.class), 0);
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "target");
        result.addError(new FieldError("target", "arg", "invalid argument"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, result);

        String body = jsonBody("validation failed", ex, IncludePolicy.ALWAYS);

        assertTrue(body.contains("\"arg\":\"invalid argument\""));
    }

    @Test
    void extractBindingErrors_plainExceptionAndEmptyResultYieldNoErrors() {
        assertFalse(jsonBody("m", new IllegalStateException("plain"), IncludePolicy.ALWAYS).contains("\"errors\""));
        assertFalse(jsonBody("m", null, IncludePolicy.ALWAYS).contains("\"errors\""));

        BeanPropertyBindingResult empty = new BeanPropertyBindingResult(new Object(), "target");
        assertFalse(jsonBody("m", new BindException(empty), IncludePolicy.ALWAYS).contains("\"errors\""));
    }

    @Test
    void problemDetails_includesAllOptionalFields() {
        ErrorResponseConfig cfg = new ErrorResponseConfig(IncludePolicy.ALWAYS, IncludePolicy.ALWAYS,
                IncludePolicy.ALWAYS, false, "/error", true);
        ErrorPageRenderer.ErrorResponseBody body = ErrorPageRenderer.build(HttpStatus.NOT_FOUND, "detail\"quoted",
                bindException("name", "must not be blank"), cfg, false, false);

        assertTrue(body.getContentType().startsWith("application/problem+json"));
        String json = body.getBody();
        assertTrue(json.contains("\"type\":\"about:blank\""));
        assertTrue(json.contains("\"status\":404"));
        assertTrue(json.contains("\"title\":\"404 NOT_FOUND\""));
        assertTrue(json.contains("\"detail\":\"detail\\\"quoted\""));
        assertTrue(json.contains("\"instance\":\"/error\""));
        assertTrue(json.contains("\"trace\":"));
        assertTrue(json.contains("\"errors\":{"));
    }

    @Test
    void problemDetails_omitsAllNullOptionalFields() {
        ErrorResponseConfig cfg = new ErrorResponseConfig(IncludePolicy.NEVER, IncludePolicy.NEVER,
                IncludePolicy.NEVER, false, null, true);
        String json = ErrorPageRenderer.build(HttpStatus.BAD_REQUEST, "hidden", null, cfg, false, false).getBody();

        assertFalse(json.contains("\"detail\""));
        assertFalse(json.contains("\"instance\""));
        assertFalse(json.contains("\"trace\""));
        assertFalse(json.contains("\"errors\""));
    }

    private static class Sample {
        void handle(String value) {
        }
    }
}

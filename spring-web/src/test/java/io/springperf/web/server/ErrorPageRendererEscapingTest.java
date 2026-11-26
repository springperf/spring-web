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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.springperf.web.server.ErrorResponseConfig.IncludePolicy;

/** 错误响应体的转义、Accept 协商、绑定错误提取与 problem+json 可选字段矩阵。 */
class ErrorPageRendererEscapingTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ErrorResponseConfig jsonConfig(IncludePolicy message, IncludePolicy binding) {
        return new ErrorResponseConfig(IncludePolicy.NEVER, message, binding, false, "/error", false);
    }

    private static String jsonBody(String message, Throwable cause, IncludePolicy binding) {
        return ErrorPageRenderer
                .build(HttpStatus.BAD_REQUEST, message, cause, jsonConfig(IncludePolicy.ALWAYS, binding), false, false)
                .getBody();
    }

    /**
     * 解析响应体：字符串拼接出的畸形 JSON（漏转义、多引号等）会在这里直接失败， 而不是靠子串匹配「看起来对」。
     */
    private static JsonNode json(String body) {
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("错误响应体必须是合法 JSON，实际: " + body, e);
        }
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
            assertTrue(body.contains("\"message\":\"" + entry.getValue() + "\""), "控制字符应转义为 " + entry.getValue());
            assertEquals(entry.getKey(), json(body).get("message").asText(), "转义后解码必须还原原值");
        }
    }

    @Test
    void jsonString_escapesOtherControlCharactersAsUnicode() {
        for (int c : new int[] { 0x00, 0x01, 0x1f }) {
            String raw = String.valueOf((char) c);
            String body = jsonBody(raw, null, IncludePolicy.NEVER);
            assertTrue(body.contains("\\u%04x".formatted(c)),
                    "0x" + Integer.toHexString(c) + " 应转义为 \\u 形式（裸控制字符会破坏 JSON）");
            assertEquals(raw, json(body).get("message").asText(), "解码后应还原该控制字符");
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

        JsonNode node = json(body.getBody());
        assertEquals(500, node.get("status").asInt());
        assertEquals("500 Internal Server Error", node.get("error").asText());
        assertEquals("boom", node.get("message").asText());
    }

    @Test
    void acceptsHtml_negotiatesContentType() {
        ErrorResponseConfig cfg = new ErrorResponseConfig(IncludePolicy.NEVER, IncludePolicy.NEVER, IncludePolicy.NEVER,
                true, "/error", false);

        for (String accept : new String[] { null, "", "   ", "*/*", "text/html", "TEXT/HTML" }) {
            ErrorPageRenderer.ErrorResponseBody body = ErrorPageRenderer.build(HttpStatus.NOT_FOUND, "m", null, cfg,
                    false, false, accept);
            assertTrue(body.getContentType().startsWith("text/html"), "Accept=" + accept + " 应返回 whitelabel HTML");
        }
        for (String accept : new String[] { "application/json", "application/problem+json" }) {
            ErrorPageRenderer.ErrorResponseBody body = ErrorPageRenderer.build(HttpStatus.NOT_FOUND, "m", null, cfg,
                    false, false, accept);
            assertTrue(body.getContentType().startsWith("application/json"), "Accept=" + accept + " 应返回 JSON");
        }
    }

    @Test
    void extractBindingErrors_bindException_exposesFieldErrors() {
        String body = jsonBody("validation failed", bindException("name", "must not be blank"), IncludePolicy.ALWAYS);

        assertEquals("must not be blank", json(body).get("errors").get("name").asText());
    }

    @Test
    void extractBindingErrors_methodArgumentNotValidException_exposesFieldErrors() throws Exception {
        MethodParameter parameter = new MethodParameter(Sample.class.getDeclaredMethod("handle", String.class), 0);
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "target");
        result.addError(new FieldError("target", "arg", "invalid argument"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, result);

        String body = jsonBody("validation failed", ex, IncludePolicy.ALWAYS);

        assertEquals("invalid argument", json(body).get("errors").get("arg").asText());
    }

    @Test
    void extractBindingErrors_plainExceptionAndEmptyResultYieldNoErrors() {
        assertFalse(json(jsonBody("m", new IllegalStateException("plain"), IncludePolicy.ALWAYS)).has("errors"));
        assertFalse(json(jsonBody("m", null, IncludePolicy.ALWAYS)).has("errors"));

        BeanPropertyBindingResult empty = new BeanPropertyBindingResult(new Object(), "target");
        assertFalse(json(jsonBody("m", new BindException(empty), IncludePolicy.ALWAYS)).has("errors"));
    }

    @Test
    void problemDetails_includesAllOptionalFields() {
        ErrorResponseConfig cfg = new ErrorResponseConfig(IncludePolicy.ALWAYS, IncludePolicy.ALWAYS,
                IncludePolicy.ALWAYS, false, "/error", true);
        ErrorPageRenderer.ErrorResponseBody body = ErrorPageRenderer.build(HttpStatus.NOT_FOUND, "detail\"quoted",
                bindException("name", "must not be blank"), cfg, false, false);

        assertTrue(body.getContentType().startsWith("application/problem+json"));
        JsonNode node = json(body.getBody());
        assertEquals("about:blank", node.get("type").asText());
        assertEquals(404, node.get("status").asInt());
        assertEquals("404 NOT_FOUND", node.get("title").asText());
        assertEquals("detail\"quoted", node.get("detail").asText(), "detail 内的引号必须转义且解码一致");
        assertEquals("/error", node.get("instance").asText());
        assertTrue(node.get("trace").asText().contains("BindException"), "trace 应携带栈信息");
        assertEquals("must not be blank", node.get("errors").get("name").asText());
    }

    @Test
    void problemDetails_omitsAllNullOptionalFields() {
        ErrorResponseConfig cfg = new ErrorResponseConfig(IncludePolicy.NEVER, IncludePolicy.NEVER, IncludePolicy.NEVER,
                false, null, true);
        JsonNode node = json(
                ErrorPageRenderer.build(HttpStatus.BAD_REQUEST, "hidden", null, cfg, false, false).getBody());

        assertFalse(node.has("detail"));
        assertFalse(node.has("instance"));
        assertFalse(node.has("trace"));
        assertFalse(node.has("errors"));
    }

    private static class Sample {
        void handle(String value) {
        }
    }
}

package io.springperf.web.server;

import org.springframework.http.HttpStatusCode;
import org.springframework.lang.Nullable;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.validation.BindingResult;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 错误响应体渲染器（对齐 Spring Boot {@code ErrorAttributes} 暴露粒度）。
 * 纯函数式、无副作用：根据 {@link ErrorResponseConfig} 与异常信息产出 HTML（whitelabel）或 JSON 响应体。
 */
public final class ErrorPageRenderer {

    private ErrorPageRenderer() {
    }

    /** 渲染结果载体：contentType 与 body 文本。 */
    public static final class ErrorResponseBody {
        private final String contentType;
        private final String body;

        ErrorResponseBody(String contentType, String body) {
            this.contentType = contentType;
            this.body = body;
        }

        public String getContentType() {
            return contentType;
        }

        public String getBody() {
            return body;
        }
    }

    /**
     * 构建错误响应体。
     *
     * @param status      状态码
     * @param message     业务传入的 message（是否暴露由 config 控制）
     * @param cause       原始异常（用于栈与绑定错误提取；可空）
     * @param cfg         错误响应策略
     * @param traceParam  on-param 模式下是否命中 trace 参数
     * @param messageParam on-param 模式下是否命中 message 参数
     */
    public static ErrorResponseBody build(HttpStatusCode status, String message, Throwable cause,
                                         ErrorResponseConfig cfg, boolean traceParam, boolean messageParam) {
        // 未提供 errors 参数命中信息时按「未命中」处理（fail-closed）：宁可少暴露绑定错误，
        // 也不因调用方未传参而把 on-param 语义放大成 always。
        return build(status, message, cause, cfg, traceParam, messageParam, false, null);
    }

    /**
     * 带 Accept 协商的构建（对齐 Boot {@code BasicErrorController} 的 produces 语义）：
     * whitelabel 仅在客户端接受 HTML（未带 Accept、{@code *&#47;*} 或含 text/html）时生效，
     * 显式 JSON 客户端（如 RestTemplate/服务间调用）返回 JSON 错误体。
     */
    public static ErrorResponseBody build(HttpStatusCode status, String message, Throwable cause,
                                         ErrorResponseConfig cfg, boolean traceParam, boolean messageParam,
                                         @Nullable String acceptHeader) {
        return build(status, message, cause, cfg, traceParam, messageParam, false, acceptHeader);
    }

    /**
     * 带 Accept 协商的完整构建。
     *
     * @param traceParam   on-param 模式下是否命中 {@code trace} 参数
     * @param messageParam on-param 模式下是否命中 {@code message} 参数
     * @param errorsParam  on-param 模式下是否命中 {@code errors} 参数（控制绑定错误暴露）
     */
    public static ErrorResponseBody build(HttpStatusCode status, String message, Throwable cause,
                                         ErrorResponseConfig cfg, boolean traceParam, boolean messageParam,
                                         boolean errorsParam, @Nullable String acceptHeader) {
        int code = status != null ? status.value() : 500;
        String reason = status != null ? status.toString() : "Internal Server Error";
        String error = status != null ? status.value() + " " + status : "500 Internal Server Error";

        boolean showMessage = cfg.includeMessage(messageParam);
        boolean showTrace = cfg.includeStacktrace(traceParam);
        Map<String, String> bindingErrors = cfg.includeBindingErrors(errorsParam) ? extractBindingErrors(cause) : null;

        String timestamp = java.time.Instant.now().toString();
        String trace = showTrace && cause != null ? stacktrace(cause) : null;
        String exposedMessage = showMessage ? message : null;

        // RFC 7807 problem+json 优先（对齐 spring.mvc.problemdetails.enabled），与 whitelabel 开关互斥
        if (cfg.isProblemDetailsEnabled()) {
            return new ErrorResponseBody("application/problem+json;charset=UTF-8",
                    buildProblemDetails(code, reason, exposedMessage, trace, bindingErrors, cfg.getErrorPath()));
        }
        if (cfg.isWhitelabelEnabled() && acceptsHtml(acceptHeader)) {
            return new ErrorResponseBody("text/html;charset=UTF-8",
                    buildHtml(code, reason, error, exposedMessage, timestamp, trace, bindingErrors, cfg.getErrorPath()));
        }
        return new ErrorResponseBody("application/json;charset=UTF-8",
                buildJson(code, error, exposedMessage, timestamp, trace, bindingErrors));
    }

    /**
     * 客户端是否接受 HTML 错误页（Boot {@code BasicErrorController} produces 语义）：
     * 未带 Accept、通配 {@code *&#47;*} 或显式 text/html 均视为接受；
     * 显式 application/json（如 RestTemplate/Jackson 客户端）则不接受。
     */
    private static boolean acceptsHtml(@Nullable String acceptHeader) {
        if (acceptHeader == null || acceptHeader.trim().isEmpty()) {
            return true;
        }
        String accept = acceptHeader.toLowerCase(java.util.Locale.ROOT);
        return accept.contains("*/*") || accept.contains("text/html");
    }

    /**
     * 构建 RFC 7807 {@code application/problem+json} 响应体：
     * {@code type}（默认 about:blank）、{@code title}（状态码 reason）、{@code status}、
     * {@code detail}（策略允许时暴露的 message）、{@code instance}（错误路径）；
     * 另附非标准扩展字段 {@code trace}/{@code errors}（策略允许时）。
     */
    private static String buildProblemDetails(int code, String reason, String message, String trace,
                                             Map<String, String> bindingErrors, String instance) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"type\":\"about:blank\",");
        sb.append("\"title\":").append(jsonString(reason)).append(',');
        sb.append("\"status\":").append(code);
        if (message != null) {
            sb.append(",\"detail\":").append(jsonString(message));
        }
        if (instance != null) {
            sb.append(",\"instance\":").append(jsonString(instance));
        }
        if (trace != null) {
            sb.append(",\"trace\":").append(jsonString(trace));
        }
        if (bindingErrors != null && !bindingErrors.isEmpty()) {
            sb.append(",\"errors\":{");
            boolean first = true;
            for (Map.Entry<String, String> e : bindingErrors.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(jsonString(e.getKey())).append(':').append(jsonString(e.getValue()));
                first = false;
            }
            sb.append('}');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String buildHtml(int code, String reason, String error, String message, String timestamp,
                                   String trace, Map<String, String> bindingErrors, String errorPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE HTML><html><head><title>").append(code).append(' ')
                .append(escapeHtml(reason)).append("</title><style>")
                .append("body{font-family:Helvetica,Arial,sans-serif;margin:40px}")
                .append("h1{font-weight:normal}h2{font-weight:normal;color:#888}</style></head><body>")
                .append("<h1>").append(code).append(' ').append(escapeHtml(reason)).append("</h1>")
                .append("<h2>").append(escapeHtml(error)).append("</h2>");
        if (message != null) {
            sb.append("<p>Message: ").append(escapeHtml(message)).append("</p>");
        }
        sb.append("<p>Path: ").append(escapeHtml(errorPath)).append("</p>")
                .append("<p>Timestamp: ").append(escapeHtml(timestamp)).append("</p>");
        if (bindingErrors != null && !bindingErrors.isEmpty()) {
            sb.append("<p>Binding errors:</p><ul>");
            for (Map.Entry<String, String> e : bindingErrors.entrySet()) {
                sb.append("<li>").append(escapeHtml(e.getKey())).append(": ")
                        .append(escapeHtml(e.getValue())).append("</li>");
            }
            sb.append("</ul>");
        }
        if (trace != null) {
            sb.append("<pre>").append(escapeHtml(trace)).append("</pre>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    private static String buildJson(int code, String error, String message, String timestamp,
                                   String trace, Map<String, String> bindingErrors) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"status\":").append(code).append(',');
        sb.append("\"error\":").append(jsonString(error)).append(',');
        sb.append("\"timestamp\":").append(jsonString(timestamp));
        if (message != null) {
            sb.append(",\"message\":").append(jsonString(message));
        }
        if (trace != null) {
            sb.append(",\"trace\":").append(jsonString(trace));
        }
        if (bindingErrors != null && !bindingErrors.isEmpty()) {
            sb.append(",\"errors\":{");
            boolean first = true;
            for (Map.Entry<String, String> e : bindingErrors.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(jsonString(e.getKey())).append(':').append(jsonString(e.getValue()));
                first = false;
            }
            sb.append('}');
        }
        sb.append('}');
        return sb.toString();
    }

    private static Map<String, String> extractBindingErrors(Throwable cause) {
        if (cause == null) {
            return null;
        }
        BindingResult bindingResult = null;
        if (cause instanceof BindException) {
            bindingResult = ((BindException) cause).getBindingResult();
        } else if (cause instanceof MethodArgumentNotValidException) {
            bindingResult = ((MethodArgumentNotValidException) cause).getBindingResult();
        }
        if (bindingResult == null) {
            return null;
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (FieldError fe : bindingResult.getFieldErrors()) {
            map.put(fe.getField(), fe.getDefaultMessage());
        }
        return map.isEmpty() ? null : map;
    }

    private static String stacktrace(Throwable cause) {
        StringWriter sw = new StringWriter();
        cause.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#x27;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String jsonString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}

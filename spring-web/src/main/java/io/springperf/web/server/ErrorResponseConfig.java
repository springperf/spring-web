package io.springperf.web.server;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebComponent;

import java.util.Locale;

/**
 * 错误响应策略配置（对齐 Spring Boot {@code server.error.*}）。启动期预解析一次，注册为
 * {@link WebComponent} 供响应写出与异常解析链路读取。
 *
 * <ul>
 *   <li>{@code include-stacktrace}：never（默认）/ on-param / always</li>
 *   <li>{@code include-message}：never（默认）/ on-param / always</li>
 *   <li>{@code include-binding-errors}：never（默认）/ on-param / always</li>
 *   <li>{@code whitelabel.enabled}：是否启用内置错误页（默认 true）</li>
 *   <li>{@code error.path}：错误页路径（默认 /error，仅作为错误页标识/转发的约定路径）</li>
 *   <li>{@code spring.mvc.problemdetails.enabled}：错误响应改为 RFC 7807
 *       {@code application/problem+json}（默认 false）</li>
 * </ul>
 */
public class ErrorResponseConfig implements WebComponent {

    public enum IncludePolicy {
        NEVER, ON_PARAM, ALWAYS
    }

    /** 默认配置：三项均 never、启用 whitelabel、错误路径 /error、problemdetails 关闭。 */
    public static final ErrorResponseConfig DEFAULT = new ErrorResponseConfig(
            IncludePolicy.NEVER, IncludePolicy.NEVER, IncludePolicy.NEVER, true, "/error", false);

    private final IncludePolicy includeStacktrace;
    private final IncludePolicy includeMessage;
    private final IncludePolicy includeBindingErrors;
    private final boolean whitelabelEnabled;
    private final String errorPath;
    private final boolean problemDetailsEnabled;

    public ErrorResponseConfig(IncludePolicy includeStacktrace, IncludePolicy includeMessage,
                              IncludePolicy includeBindingErrors, boolean whitelabelEnabled, String errorPath) {
        this(includeStacktrace, includeMessage, includeBindingErrors, whitelabelEnabled, errorPath, false);
    }

    public ErrorResponseConfig(IncludePolicy includeStacktrace, IncludePolicy includeMessage,
                              IncludePolicy includeBindingErrors, boolean whitelabelEnabled, String errorPath,
                              boolean problemDetailsEnabled) {
        this.includeStacktrace = includeStacktrace;
        this.includeMessage = includeMessage;
        this.includeBindingErrors = includeBindingErrors;
        this.whitelabelEnabled = whitelabelEnabled;
        this.errorPath = errorPath;
        this.problemDetailsEnabled = problemDetailsEnabled;
    }

    public static ErrorResponseConfig fromProperties(ApplicationProperties props) {
        return new ErrorResponseConfig(
                parsePolicy(props.get(PropertiesConstant.ERROR_INCLUDE_STACKTRACE,
                        PropertiesConstant.ERROR_INCLUDE_STACKTRACE_DEFAULT)),
                parsePolicy(props.get(PropertiesConstant.ERROR_INCLUDE_MESSAGE,
                        PropertiesConstant.ERROR_INCLUDE_MESSAGE_DEFAULT)),
                parsePolicy(props.get(PropertiesConstant.ERROR_INCLUDE_BINDING_ERRORS,
                        PropertiesConstant.ERROR_INCLUDE_BINDING_ERRORS_DEFAULT)),
                props.getBoolean(PropertiesConstant.ERROR_WHITELABEL_ENABLED,
                        PropertiesConstant.ERROR_WHITELABEL_ENABLED_DEFAULT),
                props.get(PropertiesConstant.ERROR_PATH, PropertiesConstant.ERROR_PATH_DEFAULT),
                props.getBoolean(PropertiesConstant.MVC_PROBLEM_DETAILS_ENABLED,
                        PropertiesConstant.MVC_PROBLEM_DETAILS_ENABLED_DEFAULT));
    }

    private static IncludePolicy parsePolicy(String value) {
        if (value == null) {
            return IncludePolicy.NEVER;
        }
        // Boot 配置写作 on-param（连字符），枚举为 ON_PARAM：归一化连字符为下划线
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return IncludePolicy.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return IncludePolicy.NEVER;
        }
    }

    public IncludePolicy getIncludeStacktrace() {
        return includeStacktrace;
    }

    public IncludePolicy getIncludeMessage() {
        return includeMessage;
    }

    public IncludePolicy getIncludeBindingErrors() {
        return includeBindingErrors;
    }

    public boolean isWhitelabelEnabled() {
        return whitelabelEnabled;
    }

    public String getErrorPath() {
        return errorPath;
    }

    /** 是否启用 RFC 7807 problem+json 输出（{@code spring.mvc.problemdetails.enabled}）。 */
    public boolean isProblemDetailsEnabled() {
        return problemDetailsEnabled;
    }

    /** 是否暴露异常栈：ALWAYS 恒真；ON_PARAM 需调用方传入 param 命中；其余 false。 */
    public boolean includeStacktrace(boolean onParam) {
        return includeStacktrace == IncludePolicy.ALWAYS
                || (includeStacktrace == IncludePolicy.ON_PARAM && onParam);
    }

    /** 是否暴露异常 message：语义同 {@link #includeStacktrace(boolean)}。 */
    public boolean includeMessage(boolean onParam) {
        return includeMessage == IncludePolicy.ALWAYS
                || (includeMessage == IncludePolicy.ON_PARAM && onParam);
    }

    /**
     * 是否暴露绑定（校验）错误：ALWAYS 恒真；ON_PARAM 需命中请求参数 {@code errors}
     * （对齐 Boot {@code AbstractErrorController}：参数名 {@code trace}/{@code message}/{@code errors}）。
     *
     * <p>ON_PARAM 必须真正受参数门控：否则用户配置「按需暴露」却得到「总是暴露」，
     * 字段级校验信息（可能含内部结构）会被无条件下发。</p>
     */
    public boolean includeBindingErrors(boolean onParam) {
        return includeBindingErrors == IncludePolicy.ALWAYS
                || (includeBindingErrors == IncludePolicy.ON_PARAM && onParam);
    }

    /**
     * on-param 模式是否命中指定请求参数（{@code trace} / {@code message} / {@code errors}）：
     * 参数存在且值不为 {@code false} 才算命中——对齐 Boot
     * {@code AbstractErrorController#isParamPresent}（{@code ?trace=false} 语义为显式关闭）。
     *
     * @param request 当前请求（可为 {@code null}：无上下文时视为未命中）
     * @param name    参数名
     */
    public static boolean isParamPresent(io.springperf.web.http.WebServerHttpRequest request, String name) {
        if (request == null) {
            return false;
        }
        try {
            String value = request.getParameter(name);
            return value != null && !"false".equals(value);
        } catch (Exception e) {
            return false;
        }
    }
}

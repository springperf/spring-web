package io.springperf.web.view;

import io.springperf.web.context.ApplicationProperties;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class ViewProperties {

    private ViewProperties() {
    }

    /** 多选引擎（逗号分隔）；特殊值 {@code none} 表示不注册任何内置引擎，且只能单独使用。 */
    public static final String ENGINE = "spring.web.view.engine";
    /** 未配置时不过滤引擎，由 classpath 装配决定，与 {@code ViewEngineCondition} 及配置文档一致。 */
    public static final String ENGINE_DEFAULT = "";
    /** 特殊值：不注册任何内置模板引擎（含 JSP），并跳过「无 ViewResolver」的启动校验。 */
    public static final String ENGINE_NONE = "none";
    /** 允许的外部重定向 host（逗号分隔）；未配置或为空表示不限制，保持原有行为。 */
    public static final String REDIRECT_HOSTS = "spring.web.view.redirect-hosts";

    /** 内置 ViewResolver 的 order 基准，与 {@code WebComponent} 默认 order 对齐；自定义 resolver 默认排在内置之前。 */
    public static final int VIEW_ORDER_BASE = Integer.MAX_VALUE - 10000;

    // ---- Thymeleaf（对齐 spring.thymeleaf.*）----
    public static final String THYMELEAF_PREFIX = "spring.thymeleaf.prefix";
    public static final String THYMELEAF_PREFIX_DEFAULT = "templates/";
    public static final String THYMELEAF_SUFFIX = "spring.thymeleaf.suffix";
    public static final String THYMELEAF_SUFFIX_DEFAULT = ".html";
    public static final String THYMELEAF_CACHE = "spring.thymeleaf.cache";
    public static final boolean THYMELEAF_CACHE_DEFAULT = true;
    public static final String THYMELEAF_MODE = "spring.thymeleaf.mode";
    public static final String THYMELEAF_MODE_DEFAULT = "HTML";
    public static final String THYMELEAF_CACHE_TTL = "spring.thymeleaf.cache-ttl";
    public static final String THYMELEAF_ENABLED = "spring.thymeleaf.enabled";
    public static final boolean THYMELEAF_ENABLED_DEFAULT = true;
    /** 模板文件的读取编码；响应字符集由 Content-Type 决定，不受此键控制。 */
    public static final String THYMELEAF_ENCODING = "spring.thymeleaf.encoding";
    public static final String THYMELEAF_ENCODING_DEFAULT = "UTF-8";
    public static final String THYMELEAF_ORDER = "spring.thymeleaf.order";
    public static final int THYMELEAF_ORDER_DEFAULT = VIEW_ORDER_BASE + 10;

    // ---- Freemarker（对齐 spring.freemarker.*）----
    public static final String FREEMARKER_PREFIX = "spring.freemarker.prefix";
    public static final String FREEMARKER_PREFIX_DEFAULT = "templates/";
    public static final String FREEMARKER_SUFFIX = "spring.freemarker.suffix";
    public static final String FREEMARKER_SUFFIX_DEFAULT = ".ftl";
    public static final String FREEMARKER_CACHE = "spring.freemarker.cache";
    public static final boolean FREEMARKER_CACHE_DEFAULT = true;
    public static final String FREEMARKER_ENABLED = "spring.freemarker.enabled";
    public static final boolean FREEMARKER_ENABLED_DEFAULT = true;
    public static final String FREEMARKER_TEMPLATE_LOADER_PATH = "spring.freemarker.template-loader-path";
    public static final String FREEMARKER_SETTINGS = "spring.freemarker.settings";
    public static final String FREEMARKER_CONTENT_TYPE = "spring.freemarker.content-type";
    public static final String FREEMARKER_CONTENT_TYPE_DEFAULT = "text/html;charset=UTF-8";
    public static final String FREEMARKER_ORDER = "spring.freemarker.order";
    public static final int FREEMARKER_ORDER_DEFAULT = VIEW_ORDER_BASE + 20;

    // ---- Beetl（对齐 spring.beetl.*）----
    public static final String BEETL_PREFIX = "spring.beetl.prefix";
    public static final String BEETL_PREFIX_DEFAULT = "templates/";
    public static final String BEETL_SUFFIX = "spring.beetl.suffix";
    public static final String BEETL_SUFFIX_DEFAULT = ".btl";
    public static final String BEETL_CACHE = "spring.beetl.cache";
    public static final boolean BEETL_CACHE_DEFAULT = true;
    /** Beetl 模板缓存 TTL（Duration 风格；小于等于 0 表示不过期）。 */
    public static final String BEETL_CACHE_TTL = "spring.beetl.cache-ttl";
    /** Beetl 是否启用：false 时解析器不参与视图解析，也不计入启动期校验。 */
    public static final String BEETL_ENABLED = "spring.beetl.enabled";
    public static final boolean BEETL_ENABLED_DEFAULT = true;
    public static final String BEETL_ORDER = "spring.beetl.order";
    public static final int BEETL_ORDER_DEFAULT = VIEW_ORDER_BASE + 30;

    /** JSP 视图解析器 order（实现位于 spring-web-servlet）。 */
    public static final String JSP_ORDER = "spring.web.view.jsp.order";
    public static final int JSP_ORDER_DEFAULT = VIEW_ORDER_BASE + 40;

    /** 视图默认编码（模板文件读取；响应字符集由 Content-Type 决定）。 */
    public static final String VIEW_ENCODING_DEFAULT = "UTF-8";

    /** 合法引擎名（不含 {@link #ENGINE_NONE}）。 */
    private static final Set<String> VALID_ENGINES = Set.of("thymeleaf", "freemarker", "beetl");

    /**
     * 解析 {@link #ENGINE}：未配置返回空集合（不过滤引擎，由 classpath 装配决定）；
     * 其余情形返回小写引擎名集合。非法值与 {@code none} 混写都在启动期以明确异常中断，不静默降级。
     */
    public static Set<String> parseEngines(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Set.of();
        }
        Set<String> engines = new LinkedHashSet<>();
        for (String item : raw.split(",")) {
            String name = item.trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            if (!VALID_ENGINES.contains(name) && !ENGINE_NONE.equals(name)) {
                throw new IllegalStateException("Invalid value for '" + ENGINE + "': '" + name
                        + "' (expected thymeleaf/freemarker/beetl/none)");
            }
            engines.add(name);
        }
        if (engines.isEmpty()) {
            throw new IllegalStateException("Invalid value for '" + ENGINE + "': '" + raw
                    + "' (expected a comma-separated list of thymeleaf/freemarker/beetl/none)");
        }
        if (engines.contains(ENGINE_NONE) && engines.size() > 1) {
            throw new IllegalStateException(
                    "Invalid value for '" + ENGINE + "': 'none' must be used alone, got '" + raw + "'");
        }
        return engines;
    }

    /**
     * 读取配置：直接取键，未配置回退默认值（不再双读旧键）。
     */
    public static String resolve(ApplicationProperties props, String key, String def) {
        return props.get(key, def);
    }

    /** 布尔配置：非法值启动期 fail-fast，与 {@link #resolveOrder} 口径一致，不静默当成 false。 */
    public static boolean resolveBoolean(ApplicationProperties props, String key, boolean def) {
        String v = props.get(key, null);
        if (v == null || v.trim().isEmpty()) {
            return def;
        }
        String value = v.trim();
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalStateException("Invalid boolean value for '" + key + "': '" + v
                + "' (expected true/false)");
    }

    /** Duration 风格读取（毫秒）。 */
    public static long resolveDurationMillis(ApplicationProperties props, String key, long def) {
        return props.getDurationMillis(key, def);
    }

    /** 读取整数型 order；非法值启动期 fail-fast，不静默回退默认。 */
    public static int resolveOrder(ApplicationProperties props, String key, int def) {
        String v = props.get(key, null);
        if (v == null || v.trim().isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Invalid integer value for '" + key + "': " + v, ex);
        }
    }

    /** 视图名未带后缀时补上后缀；已带后缀原样返回（三个内置引擎统一口径）。 */
    public static String withSuffix(String viewName, String suffix) {
        return suffix == null || suffix.isEmpty() || viewName.endsWith(suffix) ? viewName : viewName + suffix;
    }

    /** 去掉视图名已带的后缀（Thymeleaf 的 TemplateEngine 会自行补后缀，须传纯模板名）。 */
    public static String withoutSuffix(String viewName, String suffix) {
        return suffix != null && !suffix.isEmpty() && viewName.endsWith(suffix)
                ? viewName.substring(0, viewName.length() - suffix.length()) : viewName;
    }
}

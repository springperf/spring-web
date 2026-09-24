package io.springperf.web.view;

import io.springperf.web.context.ApplicationProperties;

public final class ViewProperties {

    private ViewProperties() {
    }

    public static final String ENGINE = "spring.web.view.engine";
    public static final String ENGINE_DEFAULT = "thymeleaf";

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
    public static final String THYMELEAF_ENCODING = "spring.thymeleaf.encoding";
    public static final String THYMELEAF_ENCODING_DEFAULT = "UTF-8";

    // ---- Freemarker（对齐 spring.freemarker.*）----
    public static final String FREEMARKER_PREFIX = "spring.freemarker.prefix";
    public static final String FREEMARKER_PREFIX_DEFAULT = "templates/";
    public static final String FREEMARKER_SUFFIX = "spring.freemarker.suffix";
    public static final String FREEMARKER_SUFFIX_DEFAULT = ".ftl";
    public static final String FREEMARKER_CACHE = "spring.freemarker.cache";
    public static final boolean FREEMARKER_CACHE_DEFAULT = true;
    public static final String FREEMARKER_TEMPLATE_LOADER_PATH = "spring.freemarker.template-loader-path";
    public static final String FREEMARKER_SETTINGS = "spring.freemarker.settings";
    public static final String FREEMARKER_CONTENT_TYPE = "spring.freemarker.content-type";
    public static final String FREEMARKER_CONTENT_TYPE_DEFAULT = "text/html;charset=UTF-8";

    // ---- Beetl（对齐 spring.beetl.*）----
    public static final String BEETL_PREFIX = "spring.beetl.prefix";
    public static final String BEETL_PREFIX_DEFAULT = "templates/";
    public static final String BEETL_SUFFIX = "spring.beetl.suffix";
    public static final String BEETL_SUFFIX_DEFAULT = ".btl";
    public static final String BEETL_CACHE = "spring.beetl.cache";
    public static final boolean BEETL_CACHE_DEFAULT = true;
    /** Beetl 模板模式（对齐 spring.beetl.mode，如 HTML）；Beetl 无原生 mode 概念，保留配置以对称 thymeleaf/freemarker。 */
    public static final String BEETL_MODE = "spring.beetl.mode";
    public static final String BEETL_MODE_DEFAULT = "HTML";
    /** Beetl 模板缓存 TTL（对齐 spring.beetl.cache-ttl，Duration 风格；小于等于 0 表示不过期）。 */
    public static final String BEETL_CACHE_TTL = "spring.beetl.cache-ttl";
    /** Beetl 是否启用（对齐 spring.beetl.enabled）：false 时 resolveViewName 直接返回 null。 */
    public static final String BEETL_ENABLED = "spring.beetl.enabled";
    public static final boolean BEETL_ENABLED_DEFAULT = true;

    /** 视图默认编码（Freemarker/Beetl 未单独配置时使用，对齐 Boot 的 UTF-8 默认）。 */
    public static final String VIEW_ENCODING_DEFAULT = "UTF-8";

    /**
     * 读取配置：直接取键，未配置回退默认值（不再双读旧键）。
     */
    public static String resolve(ApplicationProperties props, String key, String def) {
        return props.get(key, def);
    }

    public static boolean resolveBoolean(ApplicationProperties props, String key, boolean def) {
        String v = props.get(key, null);
        if (v != null) {
            return Boolean.parseBoolean(v.trim());
        }
        return def;
    }

    /** Duration 风格读取（毫秒）。 */
    public static long resolveDurationMillis(ApplicationProperties props, String key, long def) {
        return props.getDurationMillis(key, def);
    }
}

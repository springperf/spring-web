package io.springperf.web.view;

public final class ViewProperties {

    private ViewProperties() {
    }

    public static final String ENGINE = "spring.web.view.engine";
    public static final String ENGINE_DEFAULT = "thymeleaf";

    public static final String THYMELEAF_PREFIX = "spring.web.view.thymeleaf.prefix";
    public static final String THYMELEAF_PREFIX_DEFAULT = "templates/";

    public static final String THYMELEAF_SUFFIX = "spring.web.view.thymeleaf.suffix";
    public static final String THYMELEAF_SUFFIX_DEFAULT = ".html";

    public static final String THYMELEAF_CACHE = "spring.web.view.thymeleaf.cache";
    public static final boolean THYMELEAF_CACHE_DEFAULT = true;

    public static final String FREEMARKER_PREFIX = "spring.web.view.freemarker.prefix";
    public static final String FREEMARKER_PREFIX_DEFAULT = "templates/";

    public static final String FREEMARKER_SUFFIX = "spring.web.view.freemarker.suffix";
    public static final String FREEMARKER_SUFFIX_DEFAULT = ".ftl";

    public static final String FREEMARKER_CACHE = "spring.web.view.freemarker.cache";
    public static final boolean FREEMARKER_CACHE_DEFAULT = true;

    public static final String BEETL_PREFIX = "spring.web.view.beetl.prefix";
    public static final String BEETL_PREFIX_DEFAULT = "templates/";

    public static final String BEETL_SUFFIX = "spring.web.view.beetl.suffix";
    public static final String BEETL_SUFFIX_DEFAULT = ".btl";

    public static final String BEETL_CACHE = "spring.web.view.beetl.cache";
    public static final boolean BEETL_CACHE_DEFAULT = true;

    public static final String ENCODING = "spring.web.view.encoding";
    public static final String ENCODING_DEFAULT = "UTF-8";
}
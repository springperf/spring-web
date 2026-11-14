package io.springperf.web.view.freemarker;

import freemarker.template.Configuration;
import freemarker.template.Template;
import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.View;
import io.springperf.web.view.ViewCache;
import io.springperf.web.view.ViewProperties;
import io.springperf.web.view.ViewResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class FreemarkerViewResolver extends BaseWebComponent implements ViewResolver {

    /** Locale 维度缓存数量上限；Accept-Language 由外部输入，不能无限建缓存。 */
    private static final int MAX_LOCALE_CACHES = 16;

    private Configuration configuration;
    private String prefix;
    private String suffix;
    private String encoding;
    private String contentType;
    private java.util.Properties settings;
    private boolean enabled = true;
    private boolean cacheable = true;
    private int order = ViewProperties.FREEMARKER_ORDER_DEFAULT;
    /** cache=false 时用于旁路；同时作为无 locale 请求的缓存。 */
    private ViewCache viewCache = new ViewCache(true);
    /** locale 非空时按 locale 分缓存，避免每请求拼接 viewName + locale 字符串键。 */
    private final ConcurrentMap<Locale, ViewCache> localeViewCaches = new ConcurrentHashMap<>();

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        String loaderPath = webContext.getProps().get(ViewProperties.FREEMARKER_TEMPLATE_LOADER_PATH, null);
        this.prefix = (loaderPath != null)
                ? loaderPath
                : ViewProperties.resolve(webContext.getProps(),
                        ViewProperties.FREEMARKER_PREFIX, ViewProperties.FREEMARKER_PREFIX_DEFAULT);
        this.suffix = ViewProperties.resolve(webContext.getProps(),
                ViewProperties.FREEMARKER_SUFFIX, ViewProperties.FREEMARKER_SUFFIX_DEFAULT);
        this.encoding = ViewProperties.VIEW_ENCODING_DEFAULT;
        String configuredContentType = webContext.getProps().get(ViewProperties.FREEMARKER_CONTENT_TYPE,
                ViewProperties.FREEMARKER_CONTENT_TYPE_DEFAULT);
        this.contentType = configuredContentType != null ? configuredContentType
                : ViewProperties.FREEMARKER_CONTENT_TYPE_DEFAULT;
        this.enabled = ViewProperties.resolveBoolean(webContext.getProps(), ViewProperties.FREEMARKER_ENABLED,
                ViewProperties.FREEMARKER_ENABLED_DEFAULT);
        this.order = ViewProperties.resolveOrder(webContext.getProps(), ViewProperties.FREEMARKER_ORDER,
                ViewProperties.FREEMARKER_ORDER_DEFAULT);
        String settingsRaw = webContext.getProps().get(ViewProperties.FREEMARKER_SETTINGS, null);
        if (settingsRaw != null && !settingsRaw.trim().isEmpty()) {
            this.settings = parseSettings(settingsRaw);
        }
        this.cacheable = ViewProperties.resolveBoolean(webContext.getProps(), ViewProperties.FREEMARKER_CACHE,
                ViewProperties.FREEMARKER_CACHE_DEFAULT);
        this.viewCache = new ViewCache(cacheable);
        this.localeViewCaches.clear();
        initEngine(cacheable);
    }

    private void initEngine(boolean cacheable) {
        // 配置错误在启动期暴露：运行期 applyContentType 也会解析该值，放到每请求才抛异常太晚
        try {
            MediaType.parseMediaType(contentType);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid " + ViewProperties.FREEMARKER_CONTENT_TYPE
                    + ": '" + contentType + "'", ex);
        }
        this.configuration = new Configuration(Configuration.VERSION_2_3_32);
        configuration.setDefaultEncoding(encoding);
        if (settings != null) {
            try {
                configuration.setSettings(settings);
            } catch (Exception e) {
                throw new IllegalStateException("Invalid spring.freemarker.settings: " + settings, e);
            }
        }
        configuration.setClassLoaderForTemplateLoading(getClass().getClassLoader(), prefix);
        if (!cacheable) {
            configuration.setTemplateUpdateDelayMilliseconds(0);
        }
    }

    private static java.util.Properties parseSettings(String raw) {
        java.util.Properties props = new java.util.Properties();
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("Invalid freemarker setting (expected key=value): " + trimmed);
            }
            props.setProperty(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        return props;
    }

    @Override
    public View resolveViewName(String viewName, Locale locale, WebServerHttpRequest req) throws Exception {
        if (!enabled) {
            return null;
        }
        return cacheFor(locale).resolve(viewName, key -> {
            String fullName = ViewProperties.withSuffix(viewName, suffix);
            String fullPath = prefix + fullName;
            if (!new ClassPathResource(fullPath).exists()) {
                return null;
            }
            Template template = configuration.getTemplate(fullName, locale);
            return new FreemarkerView(template, contentType);
        });
    }

    /**
     * FreeMarker 的 {@link Template} 与 locale 相关，因此按 locale 分缓存； 这样缓存键只需 viewName，避免每次请求拼接
     * {@code viewName + '\0' + locale}。
     * <p>
     * locale 数量由 Accept-Language 决定，属于外部输入，超过上限时整体清空重来，避免无界增长。
     * </p>
     */
    private ViewCache cacheFor(Locale locale) {
        if (!cacheable || locale == null) {
            return viewCache;
        }
        ViewCache cached = localeViewCaches.get(locale);
        if (cached != null) {
            return cached;
        }
        if (localeViewCaches.size() >= MAX_LOCALE_CACHES) {
            localeViewCaches.clear();
        }
        return localeViewCaches.computeIfAbsent(locale, key -> new ViewCache(true));
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void invalidateCache() {
        viewCache.invalidate();
        for (ViewCache cache : localeViewCaches.values()) {
            cache.invalidate();
        }
        localeViewCaches.clear();
        // 缓存中的 View 持有 Template 实例，Configuration 也缓存模板；两者都清才能让替换生效
        if (configuration != null) {
            configuration.clearTemplateCache();
        }
    }

    private static class FreemarkerView implements View {
        private final Template template;
        private final String contentType;

        FreemarkerView(Template template, String contentType) {
            this.template = template;
            this.contentType = contentType;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public void render(Map<String, ?> model, WebServerHttpRequest req, WebServerHttpResponse resp)
                throws Exception {
            Charset charset = resp.getCharacterEncoding() != null ? resp.getCharacterEncoding()
                    : StandardCharsets.UTF_8;
            try (Writer writer = new OutputStreamWriter(resp.getBody(), charset)) {
                template.process(model, writer);
                writer.flush();
            }
        }
    }
}
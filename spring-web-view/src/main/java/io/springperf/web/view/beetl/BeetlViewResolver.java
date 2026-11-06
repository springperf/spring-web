package io.springperf.web.view.beetl;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.View;
import io.springperf.web.view.ViewCache;
import io.springperf.web.view.ViewProperties;
import io.springperf.web.view.ViewResolver;
import org.beetl.core.Configuration;
import org.beetl.core.GroupTemplate;
import org.beetl.core.Template;
import org.beetl.core.resource.ClasspathResourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

public class BeetlViewResolver extends BaseWebComponent implements ViewResolver {

    /** Beetl 禁用模板缓存的实现类（对齐 spring.beetl.cache=false）。 */
    static final String NO_CACHE_CLASS = "org.beetl.core.impl.cache.NoCache";

    private GroupTemplate groupTemplate;
    private String prefix;
    private String suffix;
    private boolean enabled = true;
    private int order = ViewProperties.BEETL_ORDER_DEFAULT;
    private ViewCache viewCache = new ViewCache(true);

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        this.prefix = ViewProperties.resolve(webContext.getProps(), ViewProperties.BEETL_PREFIX,
                ViewProperties.BEETL_PREFIX_DEFAULT);
        this.suffix = ViewProperties.resolve(webContext.getProps(), ViewProperties.BEETL_SUFFIX,
                ViewProperties.BEETL_SUFFIX_DEFAULT);
        this.enabled = ViewProperties.resolveBoolean(webContext.getProps(), ViewProperties.BEETL_ENABLED,
                ViewProperties.BEETL_ENABLED_DEFAULT);
        this.order = ViewProperties.resolveOrder(webContext.getProps(), ViewProperties.BEETL_ORDER,
                ViewProperties.BEETL_ORDER_DEFAULT);
        boolean cacheable = ViewProperties.resolveBoolean(webContext.getProps(), ViewProperties.BEETL_CACHE,
                ViewProperties.BEETL_CACHE_DEFAULT);
        this.viewCache = new ViewCache(cacheable);
        initEngine(webContext, cacheable);
    }

    private void initEngine(WebContext webContext, boolean cacheable) {
        try {
            ClassLoader classLoader = BeetlViewResolver.class.getClassLoader();
            Configuration conf = new Configuration(classLoader);
            conf.setDirectByteOutput(true);
            conf.setHtmlTagSupport(true);
            // spring.beetl.cache=false → 使用 NoCache 禁用模板缓存（默认实现为进程内缓存）
            if (!cacheable) {
                conf.setCacheClass(NO_CACHE_CLASS);
            }
            // spring.beetl.cache-ttl（Duration 风格）→ Beetl cache.duration（秒）；
            // 仅在所选缓存实现支持 TTL（如 CaffeineCache）时生效，无该依赖时为无害属性。
            long cacheTtlMs = ViewProperties.resolveDurationMillis(webContext.getProps(),
                    ViewProperties.BEETL_CACHE_TTL, -1L);
            if (cacheTtlMs > 0) {
                conf.getPs().setProperty("cache.duration", String.valueOf(cacheTtlMs / 1000L));
            }
            ClasspathResourceLoader loader = new ClasspathResourceLoader(classLoader);
            this.groupTemplate = new GroupTemplate(loader, conf, classLoader);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize Beetl template engine", e);
        }
    }

    @Override
    public View resolveViewName(String viewName, Locale locale, WebServerHttpRequest req) throws Exception {
        if (!enabled) {
            return null;
        }
        return viewCache.resolve(viewName, name -> {
            String templatePath = prefix + name + suffix;
            if (!new ClassPathResource(templatePath).exists()) {
                return null;
            }
            // Beetl 的 Template 是有状态对象，必须在每次 render 时重新获取，不能放进缓存的 View
            return new BeetlView(groupTemplate, templatePath);
        });
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    private static class BeetlView implements View {
        private final GroupTemplate groupTemplate;
        private final String templatePath;

        BeetlView(GroupTemplate groupTemplate, String templatePath) {
            this.groupTemplate = groupTemplate;
            this.templatePath = templatePath;
        }

        @Override
        public void render(Map<String, ?> model, WebServerHttpRequest req, WebServerHttpResponse resp)
                throws Exception {
            Template template = groupTemplate.getTemplate(templatePath);
            template.binding(model);
            Charset charset = resp.getCharacterEncoding() != null ? resp.getCharacterEncoding()
                    : StandardCharsets.UTF_8;
            try (Writer writer = new OutputStreamWriter(resp.getBody(), charset)) {
                template.renderTo(writer);
                writer.flush();
            }
        }
    }
}

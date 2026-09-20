package io.springperf.web.view.thymeleaf;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.DefaultWebExchangeProvider;
import io.springperf.web.view.View;
import io.springperf.web.view.ViewProperties;
import io.springperf.web.view.ViewResolver;
import io.springperf.web.view.WebExchangeProvider;
import org.springframework.core.io.ClassPathResource;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ThymeleafViewResolver extends BaseWebComponent implements ViewResolver {

    private TemplateEngine templateEngine;
    private String prefix;
    private String suffix;
    private boolean cacheable;
    private String encoding;
    private String mode;
    private long cacheTtlMs = -1L;
    private boolean enabled = true;
    /** 按 order 升序的 exchange provider 链；首个 supports() 为 true 者被使用。 */
    private List<WebExchangeProvider> exchangeProviders;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        this.prefix = ViewProperties.resolve(webContext.getProps(),
                ViewProperties.THYMELEAF_PREFIX, ViewProperties.THYMELEAF_PREFIX_DEFAULT);
        this.suffix = ViewProperties.resolve(webContext.getProps(),
                ViewProperties.THYMELEAF_SUFFIX, ViewProperties.THYMELEAF_SUFFIX_DEFAULT);
        this.cacheable = ViewProperties.resolveBoolean(webContext.getProps(),
                ViewProperties.THYMELEAF_CACHE, ViewProperties.THYMELEAF_CACHE_DEFAULT);
        this.mode = webContext.getProps().get(ViewProperties.THYMELEAF_MODE, ViewProperties.THYMELEAF_MODE_DEFAULT);
        this.cacheTtlMs = ViewProperties.resolveDurationMillis(webContext.getProps(),
                ViewProperties.THYMELEAF_CACHE_TTL, -1L);
        this.enabled = ViewProperties.resolveBoolean(webContext.getProps(),
                ViewProperties.THYMELEAF_ENABLED, ViewProperties.THYMELEAF_ENABLED_DEFAULT);
        this.encoding = webContext.getProps().get(ViewProperties.THYMELEAF_ENCODING, ViewProperties.THYMELEAF_ENCODING_DEFAULT);
        // 确保默认 provider 已注册（getWebComponentWithDefault 会在缺失时注册并返回它），
        // 随后按 order 取全量 provider 列表（AnnotationAwareOrderComparator 已排序）。
        webContext.getWebComponentWithDefault(WebExchangeProvider.class, new DefaultWebExchangeProvider());
        this.exchangeProviders = webContext.getWebComponents(WebExchangeProvider.class);
        initEngine();
    }

    private void initEngine() {
        ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setPrefix(prefix);
        templateResolver.setSuffix(suffix);
        templateResolver.setTemplateMode(TemplateMode.valueOf(
                mode != null && !mode.isEmpty() ? mode.toUpperCase(java.util.Locale.ROOT) : "HTML"));
        templateResolver.setCacheable(cacheable);
        if (cacheTtlMs > 0) {
            templateResolver.setCacheTTLMs(cacheTtlMs);
        }
        templateResolver.setCharacterEncoding(encoding);
        this.templateEngine = new TemplateEngine();
        templateEngine.setTemplateResolver(templateResolver);
    }

    /**
     * 按 order 选择第一个支持当前请求的 exchange provider；无匹配时退回默认实现。
     */
    private WebExchangeProvider selectProvider(WebServerHttpRequest req) {
        List<WebExchangeProvider> providers = this.exchangeProviders;
        if (providers != null) {
            for (WebExchangeProvider provider : providers) {
                if (provider.supports(req)) {
                    return provider;
                }
            }
        }
        return new DefaultWebExchangeProvider();
    }

    @Override
    public View resolveViewName(String viewName, Locale locale, WebServerHttpRequest req) throws Exception {
        if (!enabled) {
            return null;
        }
        String fullPath = prefix + viewName + suffix;
        if (!new ClassPathResource(fullPath).exists()) {
            return null;
        }
        return new ThymeleafView(templateEngine, viewName, encoding, this);
    }

    private static class ThymeleafView implements View {
        private final TemplateEngine engine;
        private final String viewName;
        private final String encoding;
        private final ThymeleafViewResolver resolver;

        ThymeleafView(TemplateEngine engine, String viewName, String encoding, ThymeleafViewResolver resolver) {
            this.engine = engine;
            this.viewName = viewName;
            this.encoding = encoding;
            this.resolver = resolver;
        }

        @Override
        public void render(Map<String, ?> model, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
            Locale locale = req.getLocale();
            WebExchangeProvider provider = resolver.selectProvider(req);
            ThymeleafWebContext context = new ThymeleafWebContext(model, locale, req, resp, provider);
            Charset charset = resp.getCharacterEncoding() != null ? resp.getCharacterEncoding() : Charset.forName(encoding);
            try (Writer writer = new OutputStreamWriter(resp.getBody(), charset)) {
                engine.process(viewName, context, writer);
                writer.flush();
            }
        }
    }
}

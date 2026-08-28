package io.springperf.web.view.thymeleaf;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.View;
import io.springperf.web.view.ViewProperties;
import io.springperf.web.view.ViewResolver;
import org.springframework.core.io.ClassPathResource;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Map;

public class ThymeleafViewResolver extends BaseWebComponent implements ViewResolver {

    private TemplateEngine templateEngine;
    private String prefix;
    private String suffix;
    private boolean cacheable;
    private String encoding;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        this.prefix = webContext.getProps().get(
                ViewProperties.THYMELEAF_PREFIX, ViewProperties.THYMELEAF_PREFIX_DEFAULT);
        this.suffix = webContext.getProps().get(
                ViewProperties.THYMELEAF_SUFFIX, ViewProperties.THYMELEAF_SUFFIX_DEFAULT);
        this.cacheable = webContext.getProps().getBoolean(
                ViewProperties.THYMELEAF_CACHE, ViewProperties.THYMELEAF_CACHE_DEFAULT);
        this.encoding = webContext.getProps().get(
                ViewProperties.ENCODING, ViewProperties.ENCODING_DEFAULT);
        initEngine();
    }

    private void initEngine() {
        ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setPrefix(prefix);
        templateResolver.setSuffix(suffix);
        templateResolver.setTemplateMode(TemplateMode.HTML);
        templateResolver.setCacheable(cacheable);
        templateResolver.setCharacterEncoding(encoding);
        this.templateEngine = new TemplateEngine();
        templateEngine.setTemplateResolver(templateResolver);
    }

    @Override
    public View resolveViewName(String viewName, Locale locale, WebServerHttpRequest req) throws Exception {
        String fullPath = prefix + viewName + suffix;
        if (!new ClassPathResource(fullPath).exists()) {
            return null;
        }
        return new ThymeleafView(templateEngine, viewName, encoding);
    }

    private static class ThymeleafView implements View {
        private final TemplateEngine engine;
        private final String viewName;
        private final String encoding;

        ThymeleafView(TemplateEngine engine, String viewName, String encoding) {
            this.engine = engine;
            this.viewName = viewName;
            this.encoding = encoding;
        }

        @Override
        public void render(Map<String, ?> model, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
            Locale locale = req.getLocale();
            ThymeleafWebContext context = new ThymeleafWebContext(model, locale, req, resp);
            Charset charset = resp.getCharacterEncoding() != null ? resp.getCharacterEncoding() : Charset.forName(encoding);
            try (Writer writer = new OutputStreamWriter(resp.getBody(), charset)) {
                engine.process(viewName, context, writer);
                writer.flush();
            }
        }
    }
}
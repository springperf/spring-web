package io.springperf.web.view.freemarker;

import freemarker.template.Configuration;
import freemarker.template.Template;
import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.View;
import io.springperf.web.view.ViewProperties;
import io.springperf.web.view.ViewResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;

import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Map;

public class FreemarkerViewResolver extends BaseWebComponent implements ViewResolver {

    private Configuration configuration;
    private String prefix;
    private String suffix;
    private String encoding;
    private String contentType;
    private java.util.Properties settings;

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
        this.contentType = webContext.getProps().get(ViewProperties.FREEMARKER_CONTENT_TYPE,
                ViewProperties.FREEMARKER_CONTENT_TYPE_DEFAULT);
        String settingsRaw = webContext.getProps().get(ViewProperties.FREEMARKER_SETTINGS, null);
        if (settingsRaw != null && !settingsRaw.trim().isEmpty()) {
            this.settings = parseSettings(settingsRaw);
        }
        boolean cacheable = ViewProperties.resolveBoolean(webContext.getProps(),
                ViewProperties.FREEMARKER_CACHE, ViewProperties.FREEMARKER_CACHE_DEFAULT);
        initEngine(cacheable);
    }

    private void initEngine(boolean cacheable) {
        this.configuration = new Configuration(Configuration.VERSION_2_3_33);
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
        String fullName = viewName.endsWith(suffix) ? viewName : viewName + suffix;
        String fullPath = prefix + fullName;
        if (!new ClassPathResource(fullPath).exists()) {
            return null;
        }
        Template template = configuration.getTemplate(fullName, locale);
        return new FreemarkerView(template, encoding, contentType);
    }

    private static class FreemarkerView implements View {
        private final Template template;
        private final String encoding;
        private final String contentType;

        FreemarkerView(Template template, String encoding, String contentType) {
            this.template = template;
            this.encoding = encoding;
            this.contentType = contentType;
        }

        @Override
        public void render(Map<String, ?> model, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
            if (contentType != null && !contentType.isEmpty()) {
                resp.getHeaders().setContentType(MediaType.parseMediaType(contentType));
            }
            Charset charset = resp.getCharacterEncoding() != null ? resp.getCharacterEncoding() : Charset.forName(encoding);
            try (Writer writer = new OutputStreamWriter(resp.getBody(), charset)) {
                template.process(model, writer);
                writer.flush();
            }
        }
    }
}
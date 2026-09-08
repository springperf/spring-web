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

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        this.prefix = webContext.getProps().get(
                ViewProperties.FREEMARKER_PREFIX, ViewProperties.FREEMARKER_PREFIX_DEFAULT);
        this.suffix = webContext.getProps().get(
                ViewProperties.FREEMARKER_SUFFIX, ViewProperties.FREEMARKER_SUFFIX_DEFAULT);
        this.encoding = webContext.getProps().get(
                ViewProperties.ENCODING, ViewProperties.ENCODING_DEFAULT);
        boolean cacheable = webContext.getProps().getBoolean(
                ViewProperties.FREEMARKER_CACHE, ViewProperties.FREEMARKER_CACHE_DEFAULT);
        initEngine(cacheable);
    }

    private void initEngine(boolean cacheable) {
        this.configuration = new Configuration(Configuration.VERSION_2_3_32);
        configuration.setDefaultEncoding(encoding);
        configuration.setClassLoaderForTemplateLoading(getClass().getClassLoader(), prefix);
        if (!cacheable) {
            configuration.setTemplateUpdateDelayMilliseconds(0);
        }
    }

    @Override
    public View resolveViewName(String viewName, Locale locale, WebServerHttpRequest req) throws Exception {
        String fullName = viewName.endsWith(suffix) ? viewName : viewName + suffix;
        String fullPath = prefix + fullName;
        if (!new ClassPathResource(fullPath).exists()) {
            return null;
        }
        Template template = configuration.getTemplate(fullName, locale);
        return new FreemarkerView(template, encoding);
    }

    private static class FreemarkerView implements View {
        private final Template template;
        private final String encoding;

        FreemarkerView(Template template, String encoding) {
            this.template = template;
            this.encoding = encoding;
        }

        @Override
        public void render(Map<String, ?> model, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
            Charset charset = resp.getCharacterEncoding() != null ? resp.getCharacterEncoding() : Charset.forName(encoding);
            template.setOutputEncoding(charset.name());
            try (Writer writer = new OutputStreamWriter(resp.getBody(), charset)) {
                template.process(model, writer);
                writer.flush();
            }
        }
    }
}
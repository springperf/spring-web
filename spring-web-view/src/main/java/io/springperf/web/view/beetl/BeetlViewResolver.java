package io.springperf.web.view.beetl;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.View;
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
import java.util.Locale;
import java.util.Map;

public class BeetlViewResolver extends BaseWebComponent implements ViewResolver {

    private GroupTemplate groupTemplate;
    private String prefix;
    private String suffix;
    private String encoding;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        this.prefix = webContext.getProps().get(
                ViewProperties.BEETL_PREFIX, ViewProperties.BEETL_PREFIX_DEFAULT);
        this.suffix = webContext.getProps().get(
                ViewProperties.BEETL_SUFFIX, ViewProperties.BEETL_SUFFIX_DEFAULT);
        this.encoding = webContext.getProps().get(
                ViewProperties.ENCODING, ViewProperties.ENCODING_DEFAULT);
        initEngine();
    }

    private void initEngine() {
        try {
            ClassLoader classLoader = BeetlViewResolver.class.getClassLoader();
            Configuration conf = new Configuration(classLoader);
            conf.setDirectByteOutput(true);
            conf.setHtmlTagSupport(true);
            ClasspathResourceLoader loader = new ClasspathResourceLoader(classLoader);
            this.groupTemplate = new GroupTemplate(loader, conf, classLoader);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize Beetl template engine", e);
        }
    }

    @Override
    public View resolveViewName(String viewName, Locale locale, WebServerHttpRequest req) throws Exception {
        String templatePath = prefix + viewName + suffix;
        if (!new ClassPathResource(templatePath).exists()) {
            return null;
        }
        return new BeetlView(groupTemplate, templatePath, encoding);
    }

    private static class BeetlView implements View {
        private final GroupTemplate groupTemplate;
        private final String templatePath;
        private final String encoding;

        BeetlView(GroupTemplate groupTemplate, String templatePath, String encoding) {
            this.groupTemplate = groupTemplate;
            this.templatePath = templatePath;
            this.encoding = encoding;
        }

        @Override
        public void render(Map<String, ?> model, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
            Template template = groupTemplate.getTemplate(templatePath);
            template.binding(model);
            Charset charset = resp.getCharacterEncoding() != null ? resp.getCharacterEncoding() : Charset.forName(encoding);
            try (Writer writer = new OutputStreamWriter(resp.getBody(), charset)) {
                template.renderTo(writer);
                writer.flush();
            }
        }
    }
}
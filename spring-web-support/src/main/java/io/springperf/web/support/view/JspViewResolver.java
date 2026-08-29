package io.springperf.web.support.view;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.support.servlet.JasperJspServlet;
import io.springperf.web.support.servlet.PerfServletConfig;
import io.springperf.web.support.servlet.ServletInvoker;
import io.springperf.web.support.servlet.context.PerfServletContext;
import io.springperf.web.view.View;
import io.springperf.web.view.ViewResolver;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

/**
 * 基于 JSP 的 {@link ViewResolver}。
 *
 * <p>Phase 1 将 Apache Jasper 的 {@link JasperJspServlet} 注册为框架路由
 * （{@code /**&#47;*.jsp}），JSP 请求命中后由 Jasper 编译渲染。
 *
 * <p>视图名匹配规则（不匹配时返回 null，交其他 resolver 处理）：
 * <ul>
 *   <li>{@code jsp:hello} 前缀形式 → {@code prefix + hello + suffix}</li>
 *   <li>{@code hello.jsp} 后缀形式 → {@code prefix + hello.jsp}</li>
 * </ul>
 */
@Slf4j
public class JspViewResolver extends BaseWebComponent implements ViewResolver {

    public static final String JSP_PREFIX = "jsp:";
    public static final String JSP_SUFFIX = ".jsp";

    private final String prefix;
    private final String suffix;

    public JspViewResolver() {
        this("/jsp/", JSP_SUFFIX);
    }

    public JspViewResolver(String prefix, String suffix) {
        this.prefix = prefix;
        this.suffix = suffix;
    }

    @Override
    public void initComponentPhase1() throws Exception {
        MappingRegistry mappingRegistry = getWebContext().getWebComponent(MappingRegistry.class);
        if (mappingRegistry == null) {
            log.warn("MappingRegistry not available, JSP routes will not be registered");
            return;
        }
        PerfServletContext servletContext = getWebContext().getWebComponent(PerfServletContext.class);
        JasperJspServlet jspServlet = new JasperJspServlet();
        jspServlet.init(new PerfServletConfig("jsp", servletContext));
        PathMappingContext mappingContext = new PathMappingContext(new ServletInvoker(jspServlet), "/**/*.jsp");
        mappingRegistry.registerMapping(mappingContext);
        log.info("Mapped JSP servlet -> /**/*.jsp");
    }

    @Override
    public View resolveViewName(String viewName, Locale locale, WebServerHttpRequest req) {
        if (viewName == null) {
            return null;
        }
        if (viewName.startsWith(JSP_PREFIX)) {
            String name = viewName.substring(JSP_PREFIX.length());
            return new JspView(prefix + name + suffix);
        }
        if (viewName.endsWith(JSP_SUFFIX)) {
            return new JspView(prefix + viewName);
        }
        return null;
    }
}

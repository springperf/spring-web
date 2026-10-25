package io.springperf.web.support.view;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.support.servlet.JasperJspServlet;
import io.springperf.web.support.servlet.PerfServletConfig;
import io.springperf.web.support.servlet.ServletInvoker;
import io.springperf.web.support.servlet.context.PerfServletContext;
import io.springperf.web.view.View;
import io.springperf.web.view.ViewProperties;
import io.springperf.web.view.ViewResolver;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 基于 JSP 的 {@link ViewResolver}。
 * <p>
 * Phase 1 将 Apache Jasper 的 {@link JasperJspServlet} 注册为框架路由 （{@code /**&#47;*.jsp}），JSP 请求命中后由 Jasper 编译渲染。
 * <p>
 * 视图名匹配规则（不匹配时返回 null，交其他 resolver 处理）：
 * <ul>
 * <li>{@code jsp:hello} 前缀形式 → {@code prefix + hello + suffix}</li>
 * <li>{@code hello.jsp} 后缀形式 → {@code prefix + hello.jsp}</li>
 * </ul>
 */
@Slf4j
public class JspViewResolver extends BaseWebComponent implements ViewResolver {

    public static final String JSP_PREFIX = "jsp:";
    public static final String JSP_SUFFIX = ".jsp";

    /** Jasper 的 {@code development} 参数名（拼写与 Jasper 完全一致，错一个字母即静默失效）。 */
    public static final String JASPER_PARAM_DEVELOPMENT = "development";
    /** Jasper 的 {@code keepgenerated} 参数名。 */
    public static final String JASPER_PARAM_KEEP_GENERATED = "keepgenerated";
    /** Jasper 的 {@code trimSpaces} 参数名（注意大写 S）。 */
    public static final String JASPER_PARAM_TRIM_SPACES = "trimSpaces";

    private final String prefix;
    private final String suffix;
    private int order = ViewProperties.JSP_ORDER_DEFAULT;

    public JspViewResolver() {
        this("/jsp/", JSP_SUFFIX);
    }

    public JspViewResolver(String prefix, String suffix) {
        this.prefix = prefix;
        this.suffix = suffix;
    }

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        ApplicationProperties props = webContext.getProps();
        if (props != null) {
            this.order = ViewProperties.resolveOrder(props, ViewProperties.JSP_ORDER,
                    ViewProperties.JSP_ORDER_DEFAULT);
        }
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public void initComponentPhase1() throws Exception {
        MappingRegistry mappingRegistry = getWebContext().getWebComponent(MappingRegistry.class);
        if (mappingRegistry == null) {
            log.warn("MappingRegistry not available, JSP routes will not be registered");
            return;
        }
        PerfServletContext servletContext = getWebContext().getWebComponent(PerfServletContext.class);
        Map<String, String> initParameters = buildJspInitParameters(getWebContext().getProps());
        JasperJspServlet jspServlet = new JasperJspServlet(getWebContext());
        jspServlet.init(new PerfServletConfig("jsp", servletContext, initParameters));
        PathMappingContext mappingContext = new PathMappingContext(new ServletInvoker(jspServlet), "/**/*.jsp");
        mappingRegistry.registerMapping(mappingContext);
        log.info("Mapped JSP servlet -> /**/*.jsp (development={}, jasper init-parameters={})",
                initParameters.get(JASPER_PARAM_DEVELOPMENT), initParameters);
    }

    /**
     * 把 {@code server.servlet.jsp.*} 映射为 Jasper 的 init-param。
     * <p>
     * 先写入三个类型化配置键（属性名为 kebab-case，参数名用 Jasper 的原始拼写），再让 {@code server.servlet.jsp.init-parameters.*} 原样覆盖同名项——这样常用项有
     * IDE 补全与默认值， 而 Jasper 的其余参数（{@code fork}、 {@code genStringAsCharArray}、{@code engineOptionsClass} 等）仍可透传。
     * </p>
     *
     * @param props
     *            配置访问器，允许为 null（脱离 Spring 容器使用时回退 Jasper 自身默认值）
     *
     * @return 传给 {@code JspServlet.init} 的参数表（不可变）
     */
    static Map<String, String> buildJspInitParameters(ApplicationProperties props) {
        if (props == null) {
            return Collections.emptyMap();
        }
        Map<String, String> parameters = new LinkedHashMap<>(8);
        parameters.put(JASPER_PARAM_DEVELOPMENT,
                Boolean.toString(props.getBoolean(PropertiesConstant.SERVLET_JSP_DEVELOPMENT,
                        PropertiesConstant.SERVLET_JSP_DEVELOPMENT_DEFAULT)));
        parameters.put(JASPER_PARAM_KEEP_GENERATED,
                Boolean.toString(props.getBoolean(PropertiesConstant.SERVLET_JSP_KEEP_GENERATED,
                        PropertiesConstant.SERVLET_JSP_KEEP_GENERATED_DEFAULT)));
        parameters.put(JASPER_PARAM_TRIM_SPACES,
                Boolean.toString(props.getBoolean(PropertiesConstant.SERVLET_JSP_TRIM_SPACES,
                        PropertiesConstant.SERVLET_JSP_TRIM_SPACES_DEFAULT)));

        String prefix = PropertiesConstant.SERVLET_JSP_INIT_PARAMETERS_PREFIX;
        List<String> keys = props.getPropertyNames(prefix);
        if (keys != null) {
            for (String key : keys) {
                String name = key.substring(prefix.length());
                if (name.isEmpty()) {
                    continue;
                }
                String value = props.get(key, null);
                if (value != null) {
                    parameters.put(name, value);
                }
            }
        }
        return Collections.unmodifiableMap(parameters);
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

package io.springperf.web.support.servlet;

import io.springperf.web.context.WebContext;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.jsp.JspFactory;
import org.apache.jasper.compiler.TldCache;
import org.apache.jasper.runtime.JspFactoryImpl;
import org.apache.jasper.servlet.JspServlet;
import org.apache.jasper.servlet.TldScanner;
import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.SimpleInstanceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

/**
 * 基于 Apache Jasper 的 JSP 处理 Servlet。
 * <p>
 * 补充标准容器职责（非 Tomcat 容器中 Tomcat 不会自动提供）：
 * <ol>
 * <li>初始化 {@link JspFactory}（JSP 运行时入口）；</li>
 * <li>为 {@link ServletContext} 设置 {@link InstanceManager}（Jasper 实例化 JSP/Tag 类所需）；</li>
 * <li>扫描 classpath 的 TLD 并设置 {@link TldCache}（JSTL / taglib 支持）。</li>
 * </ol>
 * <p>
 * 通过 {@link ServletInvoker} 注册为框架路由（*.jsp），请求命中后由 {@code JspServlet.service()} 完成 JSP 编译与渲染。
 */
public class JasperJspServlet extends JspServlet {

    private static final Logger log = LoggerFactory.getLogger(JasperJspServlet.class);

    private static final long serialVersionUID = 1L;

    /** 可为 null（脱离 Spring 容器单独使用）；仅用于解析 InstanceManager。 */
    private final transient WebContext webContext;

    public JasperJspServlet() {
        this(null);
    }

    /**
     * @param webContext
     *            框架上下文，用于把 Jasper 的实例管理接到 Spring 容器；允许为 null
     */
    public JasperJspServlet(WebContext webContext) {
        this.webContext = webContext;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        if (JspFactory.getDefaultFactory() == null) {
            JspFactory.setDefaultFactory(new JspFactoryImpl());
        }
        ServletContext servletContext = config.getServletContext();
        servletContext.setAttribute(InstanceManager.class.getName(), resolveInstanceManager(servletContext));
        initTldCache(servletContext);
        super.init(config);
    }

    /**
     * 解析 JSP 的实例管理器，优先级从高到低：
     * <ol>
     * <li>ServletContext 上已存在（Tomcat 等容器提供，或用户预先设置）——直接复用，不覆盖；</li>
     * <li>Spring 容器中的 {@link InstanceManager} bean——用户完全自定义实例化策略；</li>
     * <li>{@link SpringInstanceManager}——JSP 生成类获得依赖注入与生命周期回调；</li>
     * <li>{@link SimpleInstanceManager}——没有 Spring 上下文时的兜底。</li>
     * </ol>
     */
    private InstanceManager resolveInstanceManager(ServletContext servletContext) {
        Object existing = servletContext.getAttribute(InstanceManager.class.getName());
        if (existing instanceof InstanceManager) {
            log.debug("JSP InstanceManager: reuse existing {}", existing.getClass().getName());
            return (InstanceManager) existing;
        }
        if (webContext != null && webContext.getCtx() != null) {
            InstanceManager bean = webContext.getBeanFromCtx(InstanceManager.class);
            if (bean != null) {
                log.info("JSP InstanceManager: using Spring bean {}", bean.getClass().getName());
                return bean;
            }
            AutowireCapableBeanFactory beanFactory = webContext.getCtx().getAutowireCapableBeanFactory();
            log.info("JSP InstanceManager: SpringInstanceManager "
                    + "(JSP/tag instances get dependency injection and lifecycle callbacks)");
            return new SpringInstanceManager(beanFactory);
        }
        log.debug("JSP InstanceManager: SimpleInstanceManager (no Spring ApplicationContext)");
        return new SimpleInstanceManager();
    }

    private static void initTldCache(ServletContext servletContext) {
        if (TldCache.getInstance(servletContext) != null) {
            return;
        }
        try {
            TldScanner scanner = new TldScanner(servletContext, true, true, false);
            scanner.scan();
            TldCache tldCache = new TldCache(servletContext, scanner.getUriTldResourcePathMap(),
                    scanner.getTldResourcePathTaglibXmlMap());
            servletContext.setAttribute(TldCache.SERVLET_CONTEXT_ATTRIBUTE_NAME, tldCache);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to scan JSP tag libraries", e);
        }
    }
}

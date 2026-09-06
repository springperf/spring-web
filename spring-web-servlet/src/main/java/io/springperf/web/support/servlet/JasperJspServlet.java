package io.springperf.web.support.servlet;

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

/**
 * 基于 Apache Jasper 的 JSP 处理 Servlet。
 *
 * <p>补充标准容器职责（非 Tomcat 容器中 Tomcat 不会自动提供）：
 * <ol>
 *   <li>初始化 {@link JspFactory}（JSP 运行时入口）；</li>
 *   <li>为 {@link ServletContext} 设置 {@link InstanceManager}（Jasper 实例化 JSP/Tag 类所需）；</li>
 *   <li>扫描 classpath 的 TLD 并设置 {@link TldCache}（JSTL / taglib 支持）。</li>
 * </ol>
 *
 * <p>通过 {@link ServletInvoker} 注册为框架路由（*.jsp），请求命中后由
 * {@code JspServlet.service()} 完成 JSP 编译与渲染。
 */
public class JasperJspServlet extends JspServlet {

    @Override
    public void init(ServletConfig config) throws ServletException {
        if (JspFactory.getDefaultFactory() == null) {
            JspFactory.setDefaultFactory(new JspFactoryImpl());
        }
        ServletContext servletContext = config.getServletContext();
        servletContext.setAttribute(InstanceManager.class.getName(), new SimpleInstanceManager());
        initTldCache(servletContext);
        super.init(config);
    }

    private static void initTldCache(ServletContext servletContext) {
        if (TldCache.getInstance(servletContext) != null) {
            return;
        }
        try {
            TldScanner scanner = new TldScanner(servletContext, true, true, false);
            scanner.scan();
            TldCache tldCache = new TldCache(servletContext,
                    scanner.getUriTldResourcePathMap(),
                    scanner.getTldResourcePathTaglibXmlMap());
            servletContext.setAttribute(TldCache.SERVLET_CONTEXT_ATTRIBUTE_NAME, tldCache);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to scan JSP tag libraries", e);
        }
    }
}

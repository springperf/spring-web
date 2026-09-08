package io.springperf.web.support.servlet;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.jsp.JspFactory;
import org.apache.jasper.compiler.TldCache;
import org.apache.jasper.runtime.JspFactoryImpl;
import org.apache.jasper.servlet.JspServlet;
import org.apache.jasper.servlet.TldScanner;
import org.apache.tomcat.InstanceManager;
import org.apache.tomcat.SimpleInstanceManager;

/**
 * 鍩轰簬 Apache Jasper 鐨?JSP 澶勭悊 Servlet銆?
 *
 * <p>琛ュ厖鏍囧噯瀹瑰櫒鑱岃矗锛堥潪 Tomcat 瀹瑰櫒涓?Tomcat 涓嶄細鑷姩鎻愪緵锛夛細
 * <ol>
 *   <li>鍒濆鍖?{@link JspFactory}锛圝SP 杩愯鏃跺叆鍙ｏ級锛?/li>
 *   <li>涓?{@link ServletContext} 璁剧疆 {@link InstanceManager}锛圝asper 瀹炰緥鍖?JSP/Tag 绫绘墍闇€锛夛紱</li>
 *   <li>鎵弿 classpath 鐨?TLD 骞惰缃?{@link TldCache}锛圝STL / taglib 鏀寔锛夈€?/li>
 * </ol>
 *
 * <p>閫氳繃 {@link ServletInvoker} 娉ㄥ唽涓烘鏋惰矾鐢憋紙*.jsp锛夛紝璇锋眰鍛戒腑鍚庣敱
 * {@code JspServlet.service()} 瀹屾垚 JSP 缂栬瘧涓庢覆鏌撱€?
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

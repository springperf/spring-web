package io.springperf.web.support.servlet;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.support.servlet.context.PerfServletContext;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebInitParam;
import javax.servlet.annotation.WebServlet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 鎵弿 Spring 瀹瑰櫒涓殑 {@link Servlet} Bean锛屽皢鍏舵敞鍐屼负妗嗘灦璺敱銆?
 *
 * <p>澶嶇敤鏍稿績鐨勯潪鎺у埗鍣ㄨ矾鐢辨満鍒讹細姣忎釜 servlet 鍖呰涓?{@link ServletInvoker}锛?
 * 浠?{@link PathMappingContext} 褰㈠紡娉ㄥ唽杩?{@link MappingRegistry}銆俿ervlet 鐨?
 * url-pattern锛堟潵鑷?{@link WebServlet} 娉ㄨВ锛夎浆鎹负妗嗘灦鐨?ant 璺緞瑙勫垯銆?
 *
 * <p>鐢熷懡鍛ㄦ湡锛歅hase 1 娉ㄥ唽璺敱骞惰皟鐢?{@code servlet.init()}锛岄攢姣佹椂璋冪敤
 * {@code servlet.destroy()}銆?
 */
@Slf4j
public class SupportServletRegistry extends BaseWebComponent {

    private final List<Servlet> initializedServlets = new ArrayList<>();

    @Override
    public void initComponentPhase1() throws Exception {
        MappingRegistry mappingRegistry = getWebContext().getWebComponent(MappingRegistry.class);
        if (mappingRegistry == null) {
            log.warn("MappingRegistry not available, Servlet routes will not be registered");
            return;
        }
        PerfServletContext servletContext = getWebContext().getWebComponent(PerfServletContext.class);
        Map<String, Servlet> beans = new LinkedHashMap<>(getWebContext().getCtx().getBeansOfType(Servlet.class));
        for (Servlet servlet : beans.values()) {
            registerServlet(servlet, servletContext, mappingRegistry);
        }
    }

    @Override
    public void destroyComponent() throws Exception {
        for (Servlet servlet : initializedServlets) {
            servlet.destroy();
        }
        initializedServlets.clear();
    }

    private void registerServlet(Servlet servlet, PerfServletContext servletContext, MappingRegistry mappingRegistry) throws ServletException {
        WebServlet webServlet = AnnotatedElementUtils.findMergedAnnotation(servlet.getClass(), WebServlet.class);
        String servletName = resolveServletName(webServlet, servlet);
        String[] urlPatterns = resolveUrlPatterns(webServlet);
        if (urlPatterns == null) {
            // 无 @WebServlet 且无显式 URL pattern：无法确定路由，跳过注册避免 /** 全路径通配遮蔽控制器
            log.warn("Servlet {} has no @WebServlet url-pattern, skipping route registration. "
                    + "Annotate it with @WebServlet(urlPatterns=...) to expose it as a route.",
                    servlet.getClass().getName());
            return;
        }
        servlet.init(new PerfServletConfig(servletName, servletContext, resolveInitParams(webServlet)));
        initializedServlets.add(servlet);
        ServletInvoker invoker = new ServletInvoker(servlet);
        for (String pattern : urlPatterns) {
            String pathRule = toPathRule(pattern);
            PathMappingContext mappingContext = new PathMappingContext(invoker, pathRule);
            mappingRegistry.registerMapping(mappingContext);
            log.info("Mapped Servlet {} -> {}", servletName, pathRule);
        }
    }

    private String resolveServletName(WebServlet webServlet, Servlet servlet) {
        if (webServlet != null && !webServlet.name().isEmpty()) {
            return webServlet.name();
        }
        return servlet.getClass().getName();
    }

    /**
     * 解析 servlet 的 url-pattern。无 {@link WebServlet} 注解时返回 {@code null}（跳过注册）。
     */
    static String[] resolveUrlPatterns(WebServlet webServlet) {
        if (webServlet != null) {
            String[] patterns = webServlet.urlPatterns();
            if (patterns.length == 0) {
                patterns = webServlet.value();
            }
            if (patterns.length > 0) {
                return patterns;
            }
        }
        return null;
    }

    private Map<String, String> resolveInitParams(WebServlet webServlet) {
        if (webServlet == null) {
            return Collections.emptyMap();
        }
        WebInitParam[] initParams = webServlet.initParams();
        if (initParams == null || initParams.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>(initParams.length);
        for (WebInitParam param : initParams) {
            result.put(param.name(), param.value());
        }
        return result;
    }

    /**
     * 灏?servlet url-pattern 杞崲涓烘鏋剁殑 ant 璺緞瑙勫垯锛?
     * <ul>
     *   <li>榛樿鏄犲皠 {@code /} 鈫?鍏ㄨ矾寰勯€氶厤</li>
     *   <li>璺緞鏄犲皠 {@code /foo/*} 鈫?{@code /foo/**}</li>
     *   <li>鍚庣紑鏄犲皠锛堝 {@code *.txt}锛夆啋 鍏ㄨ矾寰勫悗缂€鍖归厤</li>
     *   <li>绮剧‘鍖归厤 {@code /foo} 鈫?{@code /foo}</li>
     * </ul>
     */
    static String toPathRule(String urlPattern) {
        if (urlPattern == null) {
            return "/**";
        }
        String p = urlPattern.trim();
        if (p.isEmpty() || "/".equals(p)) {
            return "/**";
        }
        if (p.startsWith("*.")) {
            return "/**/" + p;
        }
        if (p.endsWith("/*")) {
            return p.substring(0, p.length() - 1) + "**";
        }
        return p;
    }
}

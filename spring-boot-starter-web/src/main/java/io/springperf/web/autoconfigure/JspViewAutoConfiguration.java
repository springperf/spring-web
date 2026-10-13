package io.springperf.web.autoconfigure;

import io.springperf.web.support.view.JspViewResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSP 视图渲染自动装配。
 *
 * <p>仅在 classpath 同时存在 Apache Jasper（{@code tomcat-embed-jasper}）与
 * {@code spring-web-view} 时激活。类级 {@code @ConditionalOnClass} 保证条件不满足时
 * 整个配置类被跳过，不会加载 {@link JspViewResolver}（其类签名依赖 view 接口）。</p>
 *
 * <p>激活后 {@link JspViewResolver} 作为 {@code ViewResolver} Bean 被
 * {@code ViewResolverRegistry} 自动吸收，并在 Phase 1 注册 Jasper JSP 路由。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = {"org.apache.jasper.servlet.JspServlet", "io.springperf.web.view.View",
        "io.springperf.web.support.view.JspViewResolver"})
public class JspViewAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    public JspViewResolver jspViewResolver(io.springperf.web.context.ApplicationProperties props) {
        // spring.mvc.view.prefix/suffix：仅作用于 JSP 视图（对齐 Boot 的 InternalResourceViewResolver 语义）；
        // 未配置或空串时回退框架默认（/jsp/ 与 .jsp），保持既有行为。
        String prefix = props.get(io.springperf.web.context.PropertiesConstant.MVC_VIEW_PREFIX,
                io.springperf.web.context.PropertiesConstant.MVC_VIEW_PREFIX_DEFAULT);
        String suffix = props.get(io.springperf.web.context.PropertiesConstant.MVC_VIEW_SUFFIX,
                io.springperf.web.context.PropertiesConstant.MVC_VIEW_SUFFIX_DEFAULT);
        if (prefix == null || prefix.isEmpty()) {
            prefix = io.springperf.web.context.PropertiesConstant.MVC_VIEW_PREFIX_DEFAULT;
        }
        if (suffix == null || suffix.isEmpty()) {
            suffix = io.springperf.web.context.PropertiesConstant.MVC_VIEW_SUFFIX_DEFAULT;
        }
        return new JspViewResolver(prefix, suffix);
    }
}

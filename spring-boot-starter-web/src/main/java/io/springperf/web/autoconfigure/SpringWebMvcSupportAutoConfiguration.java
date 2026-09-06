package io.springperf.web.autoconfigure;

import io.springperf.web.context.WebContext;
import io.springperf.web.support.async.stream.ResponseBodyEmitterReturnValueResolver;
import io.springperf.web.support.codec.interceptor.SupportHttpBodyCodecInterceptorRegistry;
import io.springperf.web.support.mvc.config.WebMvcConfigurerBridge;
import io.springperf.web.support.mvc.interceptor.SupportInterceptorRegistry;
import io.springperf.web.support.mvc.retval.ModelAndViewReturnValueResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringMVC 兼容层自动装配。仅在 classpath 存在 {@code spring-web-mvc-support} 时激活。
 *
 * <p>装配 Spring MVC 生态组件桥接：{@link SupportInterceptorRegistry}（扫描 Spring MVC
 * {@code HandlerInterceptor}）、{@link SupportHttpBodyCodecInterceptorRegistry}（
 * {@code RequestBodyAdvice}/{@code ResponseBodyAdvice}）、{@link WebMvcConfigurerBridge}、
 * {@code ResponseBodyEmitter} 返回值解析、{@code ModelAndView} 桥接等。</p>
 */
@Configuration
@ConditionalOnClass(name = "org.springframework.web.servlet.HandlerInterceptor")
public class SpringWebMvcSupportAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    public SupportInterceptorRegistry supportInterceptorRegistry() { return new SupportInterceptorRegistry(); }

    @Bean @ConditionalOnMissingBean
    public SupportHttpBodyCodecInterceptorRegistry supportHttpBodyCodecInterceptorRegistry() { return new SupportHttpBodyCodecInterceptorRegistry(); }

    @Bean @ConditionalOnMissingBean
    @ConditionalOnClass(name = "io.springperf.web.view.View")
    public ModelAndViewReturnValueResolver modelAndViewReturnValueResolver() { return new ModelAndViewReturnValueResolver(); }

    @Bean @ConditionalOnMissingBean
    public ResponseBodyEmitterReturnValueResolver responseBodyEmitterReturnValueResolver() { return new ResponseBodyEmitterReturnValueResolver(); }

    @Bean @ConditionalOnMissingBean
    public WebMvcConfigurerBridge webMvcConfigurerBridge(WebContext webContext) {
        WebMvcConfigurerBridge bridge = new WebMvcConfigurerBridge();
        webContext.registerWebComponent(bridge);
        return bridge;
    }
}

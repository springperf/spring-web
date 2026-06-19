package io.springperf.web.autoconfigure;

import io.springperf.web.context.WebContext;
import io.springperf.web.filter.WebFilter;
import io.springperf.web.support.SupportDispatcherHandler;
import io.springperf.web.support.arg.provider.HttpServletRequestProvider;
import io.springperf.web.support.arg.provider.HttpServletResponseProvider;
import io.springperf.web.support.arg.provider.WebRequestArgumentResolverProvider;
import io.springperf.web.support.async.stream.ResponseBodyEmitterReturnValueResolver;
import io.springperf.web.support.codec.interceptor.SupportHttpBodyCodecInterceptorRegistry;
import io.springperf.web.support.mvc.config.WebMvcConfigurerBridge;
import io.springperf.web.support.mvc.interceptor.SupportInterceptorRegistry;
import io.springperf.web.support.servlet.filter.FilterWrapper;
import io.springperf.web.support.servlet.filter.SupportWebFilterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationAwareOrderUtils;
import org.springframework.util.StringUtils;

@Configuration
@ConditionalOnClass(name = "io.springperf.web.support.servlet.context.ServletAdapterContext")
public class SpringWebSupportAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    public SupportDispatcherHandler supportDispatcherHandler() { return new SupportDispatcherHandler(); }

    @Bean @ConditionalOnMissingBean
    public SupportInterceptorRegistry supportInterceptorRegistry() { return new SupportInterceptorRegistry(); }

    @Bean @ConditionalOnMissingBean
    public SupportHttpBodyCodecInterceptorRegistry supportHttpBodyCodecInterceptorRegistry() { return new SupportHttpBodyCodecInterceptorRegistry(); }

    @Bean @ConditionalOnMissingBean
    public HttpServletRequestProvider httpServletRequestProvider() { return new HttpServletRequestProvider(); }

    @Bean @ConditionalOnMissingBean
    public HttpServletResponseProvider httpServletResponseProvider() { return new HttpServletResponseProvider(); }

    @Bean @ConditionalOnMissingBean
    public WebRequestArgumentResolverProvider webRequestArgumentResolverProvider() { return new WebRequestArgumentResolverProvider(); }

    @Bean @ConditionalOnMissingBean
    public SupportWebFilterRegistry supportWebFilterRegistry() {
        SupportWebFilterRegistry supportWebFilterRegistry = new SupportWebFilterRegistry();
        supportWebFilterRegistry.autoRegisterWebComponent(FilterRegistrationBean.class, this::createFilterWrapper);
        return supportWebFilterRegistry;
    }

    protected WebFilter createFilterWrapper(FilterRegistrationBean filterRegistrationBean) {
        String[] supportedPathRules = StringUtils.toStringArray(filterRegistrationBean.getUrlPatterns());
        Integer order = AnnotationAwareOrderUtils.findOrder(filterRegistrationBean);
        return FilterWrapper.create(filterRegistrationBean.getFilter(), supportedPathRules,
                order != null ? order : WebFilter.defaultOrder);
    }

    @Bean @ConditionalOnMissingBean
    public ResponseBodyEmitterReturnValueResolver responseBodyEmitterReturnValueResolver() { return new ResponseBodyEmitterReturnValueResolver(); }

    @Bean @ConditionalOnMissingBean
    public WebMvcConfigurerBridge webMvcConfigurerBridge(WebContext webContext) {
        WebMvcConfigurerBridge bridge = new WebMvcConfigurerBridge();
        bridge.initWithWebContext(webContext);
        // Register into WebContext so its initComponentPhase1() gets called
        // by WebComponentContainer during lifecycle
        webContext.registerWebComponent(bridge);
        return bridge;
    }
}

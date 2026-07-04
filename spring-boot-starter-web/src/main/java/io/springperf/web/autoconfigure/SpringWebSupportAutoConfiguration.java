package io.springperf.web.autoconfigure;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.filter.WebFilter;
import io.springperf.web.core.filter.WebFilterRegistration;
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
import io.springperf.web.support.servlet.session.PerfHttpSessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.AbstractFilterRegistrationBean;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationAwareOrderUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

@Slf4j
@Configuration
@ConditionalOnClass(name = "io.springperf.web.support.servlet.context.ServletAdapterContext")
public class SpringWebSupportAutoConfiguration implements ApplicationContextAware {

    private ApplicationContext applicationContext;

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
    public SupportWebFilterRegistry supportWebFilterRegistry(WebContext webContext) {
        SupportWebFilterRegistry supportWebFilterRegistry = new SupportWebFilterRegistry(webContext.getDispatcherHandler());
        supportWebFilterRegistry.autoRegisterWebComponent(AbstractFilterRegistrationBean.class, this::createFilterWrapper);
        return supportWebFilterRegistry;
    }

    protected WebFilterRegistration createFilterWrapper(AbstractFilterRegistrationBean<?> filterRegistrationBean) {
        javax.servlet.Filter filter;
        try {
            filter = filterRegistrationBean.getFilter();
        } catch (IllegalArgumentException e) {
            if (filterRegistrationBean instanceof DelegatingFilterProxyRegistrationBean) {
                try {
                    String targetBeanName = resolveTargetBeanName((DelegatingFilterProxyRegistrationBean) filterRegistrationBean);
                    if (targetBeanName == null) {
                        log.warn("DelegatingFilterProxyRegistrationBean has no targetBeanName, skipping");
                        return null;
                    }
                    filter = applicationContext.getBean(targetBeanName, javax.servlet.Filter.class);
                    log.debug("Resolved DelegatingFilterProxy target bean: {} -> {}", targetBeanName, filter.getClass().getName());
                } catch (Exception e2) {
                    log.error("Failed to resolve filter from DelegatingFilterProxyRegistrationBean", e2);
                    return null;
                }
            } else {
                return null;
            }
        }
        FilterWrapper wrapper = new FilterWrapper(filter);
        Integer order = AnnotationAwareOrderUtils.findOrder(filterRegistrationBean);
        WebFilterRegistration registration = new WebFilterRegistration(wrapper)
                .order(order != null ? order : WebFilter.defaultOrder);
        String[] urlPatterns = StringUtils.toStringArray(filterRegistrationBean.getUrlPatterns());
        if (urlPatterns.length > 0) {
            registration.addPathPatterns(urlPatterns);
        }
        return registration;
    }

    @Nullable
    private String resolveTargetBeanName(DelegatingFilterProxyRegistrationBean registration) {
        // getTargetBeanName() is protected in AbstractDelegatingFilterRegistrationBean
        Method method = ReflectionUtils.findMethod(DelegatingFilterProxyRegistrationBean.class, "getTargetBeanName");
        if (method != null) {
            ReflectionUtils.makeAccessible(method);
            return (String) ReflectionUtils.invokeMethod(method, registration);
        }
        return null;
    }

    @Bean @ConditionalOnMissingBean
    public ResponseBodyEmitterReturnValueResolver responseBodyEmitterReturnValueResolver() { return new ResponseBodyEmitterReturnValueResolver(); }

    @Bean @ConditionalOnMissingBean
    public WebMvcConfigurerBridge webMvcConfigurerBridge(WebContext webContext) {
        WebMvcConfigurerBridge bridge = new WebMvcConfigurerBridge();
        webContext.registerWebComponent(bridge);
        return bridge;
    }

    @Bean @ConditionalOnMissingBean
    public PerfHttpSessionManager perfHttpSessionManager(WebContext webContext) {
        PerfHttpSessionManager manager = new PerfHttpSessionManager();
        webContext.registerWebComponent(manager);
        return manager;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
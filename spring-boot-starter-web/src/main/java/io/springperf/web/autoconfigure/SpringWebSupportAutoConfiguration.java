package io.springperf.web.autoconfigure;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.filter.WebFilter;
import io.springperf.web.core.filter.WebFilterRegistration;
import io.springperf.web.support.SupportDispatcherHandler;
import io.springperf.web.support.arg.provider.HttpServletRequestProvider;
import io.springperf.web.support.arg.provider.HttpServletResponseProvider;
import io.springperf.web.support.arg.provider.ServletRequestProvider;
import io.springperf.web.support.arg.provider.ServletResponseProvider;
import io.springperf.web.support.arg.provider.WebRequestArgumentResolverProvider;
import io.springperf.web.support.async.stream.ResponseBodyEmitterReturnValueResolver;
import io.springperf.web.support.codec.interceptor.SupportHttpBodyCodecInterceptorRegistry;
import io.springperf.web.support.mvc.config.WebMvcConfigurerBridge;
import io.springperf.web.support.mvc.interceptor.SupportInterceptorRegistry;
import io.springperf.web.support.mvc.retval.ModelAndViewReturnValueResolver;
import io.springperf.web.support.servlet.SupportServletRegistry;
import io.springperf.web.support.servlet.context.PerfServletContext;
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
    @ConditionalOnClass(name = "io.springperf.web.view.View")
    public ModelAndViewReturnValueResolver modelAndViewReturnValueResolver() { return new ModelAndViewReturnValueResolver(); }

    @Bean @ConditionalOnMissingBean
    public HttpServletRequestProvider httpServletRequestProvider() { return new HttpServletRequestProvider(); }

    @Bean @ConditionalOnMissingBean
    public HttpServletResponseProvider httpServletResponseProvider() { return new HttpServletResponseProvider(); }

    @Bean @ConditionalOnMissingBean
    public ServletRequestProvider servletRequestProvider() { return new ServletRequestProvider(); }

    @Bean @ConditionalOnMissingBean
    public ServletResponseProvider servletResponseProvider() { return new ServletResponseProvider(); }

    @Bean @ConditionalOnMissingBean
    public WebRequestArgumentResolverProvider webRequestArgumentResolverProvider() { return new WebRequestArgumentResolverProvider(); }

    @Bean @ConditionalOnMissingBean
    public SupportWebFilterRegistry supportWebFilterRegistry(WebContext webContext) {
        SupportWebFilterRegistry supportWebFilterRegistry = new SupportWebFilterRegistry(webContext.getDispatcherHandler());
        supportWebFilterRegistry.autoRegisterWebComponent(AbstractFilterRegistrationBean.class, this::createFilterWrapper);
        return supportWebFilterRegistry;
    }

    protected WebFilterRegistration createFilterWrapper(AbstractFilterRegistrationBean<?> filterRegistrationBean) {
        jakarta.servlet.Filter filter;
        try {
            filter = filterRegistrationBean.getFilter();
        } catch (IllegalArgumentException | IllegalStateException e) {
            // SB 3.2 的 DelegatingFilterProxyRegistrationBean.getWebApplicationContext() 用
            // Assert.isInstanceOf（抛 IllegalArgumentException）；SB 3.5+ 改为 Assert.state（抛
            // IllegalStateException）。非 WebApplicationContext（本框架强制 AnnotationConfigApplicationContext）
            // 时两者都需走 fallback 直接从 Spring 容器解析目标 filter bean。
            if (filterRegistrationBean instanceof DelegatingFilterProxyRegistrationBean) {
                try {
                    String targetBeanName = resolveTargetBeanName((DelegatingFilterProxyRegistrationBean) filterRegistrationBean);
                    if (targetBeanName == null) {
                        log.warn("DelegatingFilterProxyRegistrationBean has no targetBeanName, skipping");
                        return null;
                    }
                    filter = applicationContext.getBean(targetBeanName, jakarta.servlet.Filter.class);
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

    /**
     * ServletContext 是 Servlet 桥接层的基础设施，作为独立组件注册（必然存在），
     * 不依赖 session 管理器创建。JSP/FilterWrapper/SupportServletRegistry 等直接引用。
     */
    @Bean @ConditionalOnMissingBean
    public PerfServletContext perfServletContext(WebContext webContext) {
        PerfServletContext servletContext = new PerfServletContext(webContext);
        webContext.registerWebComponent(servletContext);
        return servletContext;
    }

    @Bean @ConditionalOnMissingBean
    public PerfHttpSessionManager perfHttpSessionManager(WebContext webContext) {
        PerfHttpSessionManager manager = new PerfHttpSessionManager();
        webContext.registerWebComponent(manager);
        return manager;
    }

    @Bean @ConditionalOnMissingBean
    public SupportServletRegistry supportServletRegistry(WebContext webContext) {
        SupportServletRegistry registry = new SupportServletRegistry();
        webContext.registerWebComponent(registry);
        return registry;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
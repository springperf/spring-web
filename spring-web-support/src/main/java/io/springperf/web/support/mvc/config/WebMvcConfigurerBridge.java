package io.springperf.web.support.mvc.config;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.WebComponentWrapper;
import io.springperf.web.core.arg.ArgumentResolverRegistry;
import io.springperf.web.core.arg.RuntimeArgumentResolver;
import io.springperf.web.core.arg.databinder.WebDataBinderRegistry;
import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.core.codec.AdaptedHttpBodyConverter;
import io.springperf.web.core.codec.HttpBodyCodecRegistry;
import io.springperf.web.core.codec.HttpBodyConverter;
import io.springperf.web.core.codec.WrappedHttpBodyConverter;
import io.springperf.web.core.cors.CorsRegistry;
import io.springperf.web.core.exception.ExceptionRegistry;
import io.springperf.web.core.interceptor.InterceptorRegistry;
import io.springperf.web.core.resource.ResourceHandlerRegistry;
import io.springperf.web.core.retval.ReturnValueResolverRegistry;
import io.springperf.web.support.mvc.arg.SpringHandlerMethodArgumentResolverAdapter;
import io.springperf.web.support.mvc.exception.SpringHandlerExceptionResolverAdapter;
import io.springperf.web.support.mvc.interceptor.HandlerInterceptorWrapper;
import io.springperf.web.support.mvc.retval.SpringHandlerMethodReturnValueHandlerAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderUtils;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.converter.GenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.validation.Validator;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.ValidatorRegistration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scans all {@link WebMvcConfigurer} beans from the Spring context and bridges
 * their configuration to the framework's native registries.
 *
 * <p>Supported methods in Phase 1:
 * <ul>
 *   <li>{@link WebMvcConfigurer#addInterceptors(InterceptorRegistry)}</li>
 *   <li>{@link WebMvcConfigurer#addCorsMappings(CorsRegistry)}</li>
 *   <li>{@link WebMvcConfigurer#addResourceHandlers(ResourceHandlerRegistry)}</li>
 *   <li>{@link WebMvcConfigurer#addFormatters(FormatterRegistry)}</li>
 *   <li>{@link WebMvcConfigurer#configureAsyncSupport(AsyncSupportConfigurer)}</li>
 *   <li>{@link WebMvcConfigurer#addArgumentResolvers(List)}</li>
 *   <li>{@link WebMvcConfigurer#extendMessageConverters(List)}</li>
 *   <li>{@link WebMvcConfigurer#addReturnValueHandlers(List)}</li>
 *   <li>{@link WebMvcConfigurer#extendHandlerExceptionResolvers(List)}</li>
 *   <li>{@link WebMvcConfigurer#configureValidator(ValidatorRegistration)}</li>
 * </ul>
 *
 * <p>This component runs during {@link #initComponentPhase1()} to ensure
 * configuration is applied before the framework's registries complete their
 * own Phase 1 initialization.
 */
@Slf4j
public class WebMvcConfigurerBridge extends BaseWebComponent {

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 20000;
    }

    @Override
    public void initComponentPhase1() {
        Map<String, WebMvcConfigurer> configurers = webContext.getCtx().getBeansOfType(WebMvcConfigurer.class);
        if (configurers.isEmpty()) {
            return;
        }
        log.debug("Found {} WebMvcConfigurer bean(s)", configurers.size());

        // Create shim collectors
        org.springframework.web.servlet.config.annotation.InterceptorRegistry shimInterceptorRegistry =
                new org.springframework.web.servlet.config.annotation.InterceptorRegistry();
        org.springframework.web.servlet.config.annotation.CorsRegistry shimCorsRegistry =
                new org.springframework.web.servlet.config.annotation.CorsRegistry();
        org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry shimResourceRegistry =
                new org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry();

        // Collect configuration from all WebMvcConfigurer beans
        for (WebMvcConfigurer configurer : configurers.values()) {
            configurer.addInterceptors(shimInterceptorRegistry);
            configurer.addCorsMappings(shimCorsRegistry);
            configurer.addResourceHandlers(shimResourceRegistry);
        }

        // Bridge interceptors
        bridgeInterceptors(shimInterceptorRegistry);

        // Bridge CORS
        bridgeCorsMappings(shimCorsRegistry);

        // Bridge resource handlers
        bridgeResourceHandlers(shimResourceRegistry);

        // Bridge formatters
        bridgeFormatters(configurers);

        // Bridge async support
        bridgeAsyncSupport(configurers);

        // Bridge argument resolvers
        bridgeArgumentResolvers(configurers);

        // Bridge message converters
        bridgeMessageConverters(configurers);

        // Bridge return value handlers
        bridgeReturnValueHandlers(configurers);

        // Bridge exception resolvers
        bridgeHandlerExceptionResolvers(configurers);

        // Bridge validator
        bridgeConfigureValidator(configurers);
    }

    protected void bridgeInterceptors(
            org.springframework.web.servlet.config.annotation.InterceptorRegistry shimRegistry) {

        List<org.springframework.web.servlet.config.annotation.InterceptorRegistration> shimRegistrations =
                shimRegistry.getRegistrations();
        if (shimRegistrations == null || shimRegistrations.isEmpty()) {
            return;
        }

        InterceptorRegistry frameworkRegistry = webContext.getWebComponent(InterceptorRegistry.class);
        if (frameworkRegistry == null) {
            log.warn("InterceptorRegistry not available, skipping interceptor bridging");
            return;
        }

        for (org.springframework.web.servlet.config.annotation.InterceptorRegistration shimReg : shimRegistrations) {
            HandlerInterceptorWrapper wrapper = new HandlerInterceptorWrapper(shimReg.getInterceptor());
            io.springperf.web.core.interceptor.InterceptorRegistration frameworkReg =
                    new io.springperf.web.core.interceptor.InterceptorRegistration(wrapper);
            for (String includePattern : shimReg.getIncludePatterns()) {
                frameworkReg.addPathPatterns(includePattern);
            }
            for (String excludePattern : shimReg.getExcludePatterns()) {
                frameworkReg.excludePathPatterns(excludePattern);
            }
            frameworkReg.order(shimReg.getOrder());
            if (shimReg.getPathMatcher() != null) {
                frameworkReg.pathMatcher(shimReg.getPathMatcher());
            }
            frameworkRegistry.registerWebComponent(frameworkReg);
            log.debug("Bridged interceptor: {} with patterns={}, exclude={}",
                    shimReg.getInterceptor().getClass().getSimpleName(),
                    shimReg.getIncludePatterns(), shimReg.getExcludePatterns());
        }
    }

    protected void bridgeCorsMappings(
            org.springframework.web.servlet.config.annotation.CorsRegistry shimRegistry) {

        java.util.List<org.springframework.web.servlet.config.annotation.CorsRegistration> shimRegistrations =
                shimRegistry.getRegistrations();
        if (shimRegistrations.isEmpty()) {
            return;
        }

        CorsRegistry frameworkRegistry = webContext.getWebComponent(CorsRegistry.class);
        if (frameworkRegistry == null) {
            log.warn("CorsRegistry not available, skipping CORS bridging");
            return;
        }

        for (org.springframework.web.servlet.config.annotation.CorsRegistration shimReg : shimRegistrations) {
            io.springperf.web.core.cors.CorsRegistration frameworkReg =
                    frameworkRegistry.addMapping(shimReg.getPathPattern());
            org.springframework.web.cors.CorsConfiguration config = shimReg.getCorsConfiguration();
            if (config.getAllowedOrigins() != null) {
                frameworkReg.allowedOrigins(config.getAllowedOrigins().toArray(new String[0]));
            }
            if (config.getAllowedMethods() != null) {
                frameworkReg.allowedMethods(config.getAllowedMethods().toArray(new String[0]));
            }
            if (config.getAllowedHeaders() != null) {
                frameworkReg.allowedHeaders(config.getAllowedHeaders().toArray(new String[0]));
            }
            if (config.getExposedHeaders() != null) {
                frameworkReg.exposedHeaders(config.getExposedHeaders().toArray(new String[0]));
            }
            if (config.getAllowCredentials() != null) {
                frameworkReg.allowCredentials(config.getAllowCredentials());
            }
            if (config.getMaxAge() != null) {
                frameworkReg.maxAge(config.getMaxAge());
            }
            log.debug("Bridged CORS mapping: {} -> origins={}", shimReg.getPathPattern(), config.getAllowedOrigins());
        }
    }

    protected void bridgeResourceHandlers(
            org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry shimRegistry) {

        List<org.springframework.web.servlet.config.annotation.ResourceHandlerRegistration> shimRegistrations =
                shimRegistry.getRegistrations();
        if (shimRegistrations == null || shimRegistrations.isEmpty()) {
            return;
        }

        io.springperf.web.core.resource.ResourceHandlerRegistry resourceHandlerRegistry =
                webContext.getWebComponent(io.springperf.web.core.resource.ResourceHandlerRegistry.class);
        if (resourceHandlerRegistry == null) {
            log.warn("ResourceHandlerRegistry not available, skipping resource handler bridging");
            return;
        }

        for (org.springframework.web.servlet.config.annotation.ResourceHandlerRegistration shimReg : shimRegistrations) {
            io.springperf.web.core.resource.ResourceHandlerRegistration frameworkReg =
                    new io.springperf.web.core.resource.ResourceHandlerRegistration(shimReg.getPathPatterns());
            for (String location : shimReg.getLocationValues()) {
                frameworkReg.addResourceLocations(location);
            }
            if (shimReg.getCachePeriod() != null) {
                frameworkReg.setCachePeriod(shimReg.getCachePeriod());
            }
            if (shimReg.getCacheControl() != null) {
                frameworkReg.setCacheControl(shimReg.getCacheControl());
            }
            resourceHandlerRegistry.registerWebComponent(frameworkReg);
            log.debug("Bridged resource handler: patterns={}, locations={}", shimReg.getPathPatterns(), shimReg.getLocationValues());
        }
    }

    protected void bridgeFormatters(Map<String, WebMvcConfigurer> configurers) {
        org.springframework.core.convert.ConversionService cs =
                webContext.getBeanFromCtx(org.springframework.core.convert.ConversionService.class);
        if (cs instanceof FormatterRegistry) {
            FormatterRegistry formatterRegistry = (FormatterRegistry) cs;
            for (WebMvcConfigurer configurer : configurers.values()) {
                configurer.addFormatters(formatterRegistry);
            }
            if (log.isDebugEnabled()) {
                log.debug("Bridged formatters from {} WebMvcConfigurer bean(s) to Spring ConversionService", configurers.size());
            }
        }
    }

    protected void bridgeAsyncSupport(Map<String, WebMvcConfigurer> configurers) {
        AsyncSupportConfigurer shimConfigurer = new AsyncSupportConfigurer();
        for (WebMvcConfigurer configurer : configurers.values()) {
            configurer.configureAsyncSupport(shimConfigurer);
        }

        AsyncSupportRegistry asyncRegistry = webContext.getWebComponent(AsyncSupportRegistry.class);
        if (asyncRegistry == null) {
            log.warn("AsyncSupportRegistry not available, skipping async support bridging");
            return;
        }

        Long timeout = shimConfigurer.getTimeout();
        if (timeout != null) {
            asyncRegistry.setDefaultTimeout(timeout);
            log.debug("Bridged async default timeout: {}ms", timeout);
        }

        AsyncTaskExecutor executor = shimConfigurer.getTaskExecutor();
        if (executor != null) {
            asyncRegistry.setTaskExecutor(executor);
            log.debug("Bridged async task executor: {}", executor.getClass().getName());
        }

        List<CallableProcessingInterceptor> callableInterceptors = shimConfigurer.getCallableInterceptors();
        if (!callableInterceptors.isEmpty()) {
            asyncRegistry.addCallableInterceptors(callableInterceptors);
            log.debug("Bridged {} CallableProcessingInterceptor(s)", callableInterceptors.size());
        }

        List<DeferredResultProcessingInterceptor> deferredResultInterceptors = shimConfigurer.getDeferredResultInterceptors();
        if (!deferredResultInterceptors.isEmpty()) {
            asyncRegistry.addDeferredResultInterceptors(deferredResultInterceptors);
            log.debug("Bridged {} DeferredResultProcessingInterceptor(s)", deferredResultInterceptors.size());
        }
    }

    protected void bridgeArgumentResolvers(Map<String, WebMvcConfigurer> configurers) {
        ArgumentResolverRegistry argRegistry = webContext.getWebComponent(ArgumentResolverRegistry.class);
        if (argRegistry == null) {
            log.warn("ArgumentResolverRegistry not available, skipping argument resolver bridging");
            return;
        }

        List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();
        for (WebMvcConfigurer configurer : configurers.values()) {
            configurer.addArgumentResolvers(resolvers);
        }
        if (resolvers.isEmpty()) {
            return;
        }

        for (HandlerMethodArgumentResolver resolver : resolvers) {
            RuntimeArgumentResolver adapter = new SpringHandlerMethodArgumentResolverAdapter(resolver);
            argRegistry.addRuntimeArgumentResolver(adapter);
            log.debug("Bridged argument resolver: {}", resolver.getClass().getName());
        }
    }

    @SuppressWarnings("unchecked")
    protected void bridgeMessageConverters(Map<String, WebMvcConfigurer> configurers) {
        HttpBodyCodecRegistry codecRegistry = webContext.getWebComponent(HttpBodyCodecRegistry.class);
        if (codecRegistry == null) {
            log.warn("HttpBodyCodecRegistry not available, skipping message converter bridging");
            return;
        }

        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        for (WebMvcConfigurer configurer : configurers.values()) {
            configurer.extendMessageConverters(converters);
        }
        if (converters.isEmpty()) {
            return;
        }

        for (HttpMessageConverter<?> converter : converters) {
            HttpBodyConverter<?> bodyConverter;
            if (converter instanceof GenericHttpMessageConverter) {
                bodyConverter = new WrappedHttpBodyConverter((GenericHttpMessageConverter) converter);
            } else {
                bodyConverter = new AdaptedHttpBodyConverter(converter);
            }
            if (AnnotationAwareOrderUtils.findOrder(converter) == null) {
                ((WebComponentWrapper<?>) bodyConverter).setOrder(Ordered.LOWEST_PRECEDENCE - 20000);
            }
            codecRegistry.registerConverter(bodyConverter);
            log.debug("Bridged message converter: {} -> {} with order {}", converter.getClass().getName(),
                    bodyConverter.getClass().getSimpleName(), bodyConverter.getOrder());
        }
    }

    protected void bridgeReturnValueHandlers(Map<String, WebMvcConfigurer> configurers) {
        ReturnValueResolverRegistry retValRegistry = webContext.getWebComponent(ReturnValueResolverRegistry.class);
        if (retValRegistry == null) {
            log.warn("ReturnValueResolverRegistry not available, skipping return value handler bridging");
            return;
        }

        List<HandlerMethodReturnValueHandler> handlers = new ArrayList<>();
        for (WebMvcConfigurer configurer : configurers.values()) {
            configurer.addReturnValueHandlers(handlers);
        }
        if (handlers.isEmpty()) {
            return;
        }

        for (HandlerMethodReturnValueHandler handler : handlers) {
            SpringHandlerMethodReturnValueHandlerAdapter adapter = new SpringHandlerMethodReturnValueHandlerAdapter(handler);
            if (AnnotationAwareOrderUtils.findOrder(handler) == null) {
                adapter.setOrder(Ordered.LOWEST_PRECEDENCE - 20000);
            }
            retValRegistry.addResolver(adapter);
            log.debug("Bridged return value handler: {}", handler.getClass().getName());
        }
    }

    protected void bridgeHandlerExceptionResolvers(Map<String, WebMvcConfigurer> configurers) {
        ExceptionRegistry exceptionRegistry = webContext.getWebComponent(ExceptionRegistry.class);
        if (exceptionRegistry == null) {
            log.warn("ExceptionRegistry not available, skipping exception resolver bridging");
            return;
        }

        List<HandlerExceptionResolver> resolvers = new ArrayList<>();
        for (WebMvcConfigurer configurer : configurers.values()) {
            configurer.extendHandlerExceptionResolvers(resolvers);
        }
        if (resolvers.isEmpty()) {
            return;
        }

        for (HandlerExceptionResolver resolver : resolvers) {
            SpringHandlerExceptionResolverAdapter adapter = new SpringHandlerExceptionResolverAdapter(resolver);
            if (AnnotationAwareOrderUtils.findOrder(resolver) == null) {
                adapter.setOrder(Ordered.LOWEST_PRECEDENCE - 20000);
            }
            exceptionRegistry.addResolver(adapter);
            log.debug("Bridged exception resolver: {} with order {}", resolver.getClass().getName(), adapter.getOrder());
        }
    }

    protected void bridgeConfigureValidator(Map<String, WebMvcConfigurer> configurers) {
        // Obtain WebDataBinderRegistry through ArgumentResolverRegistry to ensure
        // we modify the same instance used during argument resolution.
        ArgumentResolverRegistry argRegistry = webContext.getWebComponent(ArgumentResolverRegistry.class);
        if (argRegistry == null) {
            log.warn("ArgumentResolverRegistry not available, skipping validator bridging");
            return;
        }
        WebDataBinderRegistry binderRegistry = argRegistry.getWebDataBinderRegistry();
        if (binderRegistry == null) {
            log.warn("WebDataBinderRegistry not available from ArgumentResolverRegistry, skipping validator bridging");
            return;
        }

        ValidatorRegistration shim = new ValidatorRegistration();
        for (WebMvcConfigurer configurer : configurers.values()) {
            configurer.configureValidator(shim);
        }

        Validator validator = shim.getValidator();
        if (validator != null) {
            binderRegistry.setDefaultValidator(validator);
            log.debug("Bridged custom validator: {}", validator.getClass().getName());
        }
    }
}

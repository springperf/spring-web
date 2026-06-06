package io.springperf.webtest.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.validation.Validator;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.*;

import javax.annotation.PostConstruct;
import java.util.List;

@Configuration
public class BridgeE2eConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(BridgeE2eConfig.class);

    @PostConstruct
    public void init() {
        log.info("BridgeE2eConfig loaded and initialized");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        log.info("BridgeE2eConfig.addArgumentResolvers called");
        resolvers.add(new CustomArgumentResolver());
        log.info("CustomArgumentResolver registered, total resolvers: {}", resolvers.size());
    }

    @Override
    public void addReturnValueHandlers(List<HandlerMethodReturnValueHandler> handlers) {
        log.info("BridgeE2eConfig.addReturnValueHandlers called");
        handlers.add(new CustomE2eReturnValueHandler());
        log.info("CustomE2eReturnValueHandler registered");
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        log.info("BridgeE2eConfig.extendMessageConverters called");
        converters.add(new CustomE2eConverter());
        log.info("CustomE2eConverter registered");
    }

    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        log.info("BridgeE2eConfig.extendHandlerExceptionResolvers called");
        resolvers.add(new CustomE2eExceptionResolver());
        log.info("CustomE2eExceptionResolver registered");
    }

    @Override
    public void configureValidator(ValidatorRegistration registration) {
        log.info("BridgeE2eConfig.configureValidator called");
        Validator validator = new CustomE2eValidator();
        registration.validator(validator);
        log.info("CustomE2eValidator registered: {}", validator.getClass().getName());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        log.info("BridgeE2eConfig.addInterceptors called");
        registry.addInterceptor(new CustomE2eInterceptor());
        log.info("CustomE2eInterceptor registered globally");
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        log.info("BridgeE2eConfig.addFormatters called");
        registry.addFormatter(new CustomNameFormatter());
        log.info("CustomNameFormatter registered");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        log.info("BridgeE2eConfig.addResourceHandlers called");
        registry.addResourceHandler("/bridge-static/**")
                .addResourceLocations("classpath:/bridge-static/");
        log.info("ResourceHandler registered for /bridge-static/** -> classpath:/bridge-static/");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        log.info("BridgeE2eConfig.addCorsMappings called");
        registry.addMapping("/bridge/**")
                .allowedOrigins("http://trusted-origin.com")
                .allowedMethods("GET", "POST")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
        log.info("CORS mappings registered for /bridge/**");
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        log.info("BridgeE2eConfig.configureAsyncSupport called");
        configurer.setDefaultTimeout(100L);
        log.info("Async default timeout set to 100ms");
    }
}
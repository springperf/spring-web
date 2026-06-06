/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.servlet.config.annotation;

import org.springframework.format.FormatterRegistry;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.validation.MessageCodesResolver;
import org.springframework.validation.Validator;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.List;

/**
 * Defines callback methods to customize the Java-based configuration for
 * Spring Web MVC via {@code @EnableWebMvc}.
 *
 * <p>{@code @EnableWebMvc}-annotated configuration classes may implement
 * this interface to be called back and given a chance to customize the
 * default configuration with the following options:
 *
 * <p>This interface extends {@link WebMvcConfigurer} with the ability
 * to configure additional features.
 *
 * @author Rossen Stoyanchev
 * @since 3.1
 */
public interface WebMvcConfigurer {

    /**
     * Helps with configuring HandlerMapping path matching options such as
     * whether to use parsed {@code PathPatterns} or String pattern matching
     * with {@code PathMatcher}, whether to match trailing slashes, and more.
     * @param configurer the {@link PathMatchConfigurer} to customize
     * @since 4.1
     */
    default void configurePathMatch(PathMatchConfigurer configurer) {
    }

    /**
     * Configures content negotiation options.
     * @param configurer the {@link ContentNegotiationConfigurer} to customize
     */
    default void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
    }

    /**
     * Configure asynchronous request handling options.
     * @param configurer the {@link AsyncSupportConfigurer} to customize
     * @since 4.2
     */
    default void configureAsyncSupport(AsyncSupportConfigurer configurer) {
    }

    /**
     * Configure a handler to defer to a default Servlet if the Spring
     * MVC {@code DispatcherServlet} does not find a handler for a request.
     * @param configurer the {@link DefaultServletHandlerConfigurer} to customize
     */
    default void configureDefaultServletHandling(DefaultServletHandlerConfigurer configurer) {
    }

    /**
     * Helps with registering {@code HandlerExceptionResolver} beans to
     * handle exceptions from handlers.
     * @param resolvers the list of configured resolvers
     * @since 4.2
     * @deprecated use {@link #extendHandlerExceptionResolvers(List)} instead
     */
    @Deprecated
    default void configureHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
    }

    /**
     * Extending or modify the list of exception resolvers.
     * @param resolvers the list of configured resolvers
     * @since 4.2
     */
    default void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
    }

    /**
     * Helps with configuring the {@link org.springframework.validation.Validator}
     * to use for validating model data.
     * @param validator the validator registration to customize
     * @since 4.3
     * @deprecated use {@link #configureValidator(ValidatorRegistration)} instead
     */
    @Deprecated
    default Validator getValidator() {
        return null;
    }

    /**
     * Helps with configuring the {@link org.springframework.validation.Validator}
     * to use for validating model data.
     * @param validator the validator registration to customize
     * @since 4.3
     */
    default void configureValidator(ValidatorRegistration validator) {
    }

    /**
     * Helps with configuring the {@link MessageCodesResolver}.
     * @param messageCodesResolver the message codes resolver registration
     * @since 4.3
     */
    default void configureMessageCodesResolver(MessageCodesResolver messageCodesResolver) {
    }

    /**
     * Add {@link HttpMessageConverter HttpMessageConverters} to use in the
     * framework.
     * @param converters a list to add converters to
     * @deprecated use {@link #extendMessageConverters(List)} instead
     */
    @Deprecated
    default void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
    }

    /**
     * Extend or modify the list of converters after it has been configured.
     * @param converters a list of converters to modify
     * @since 4.2
     */
    default void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    }

    /**
     * Add custom {@link HandlerMethodReturnValueHandler HandlerMethodReturnValueHandlers}
     * to use in the framework.
     * @param handlers the list of return value handlers
     * @since 4.2
     */
    default void addReturnValueHandlers(List<HandlerMethodReturnValueHandler> handlers) {
    }

    /**
     * Add custom {@link HandlerMethodArgumentResolver HandlerMethodArgumentResolvers}
     * to use in the framework.
     * @param resolvers the list of resolvers
     * @since 4.2
     */
    default void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    }

    /**
     * Configure view controllers to map requests directly to view names
     * without the need for a controller.
     * @param registry the view controller registry
     * @since 4.3
     */
    default void addViewControllers(ViewControllerRegistry registry) {
    }

    /**
     * Configure view resolvers.
     * @param registry the view resolver registry
     * @since 4.2
     */
    default void configureViewResolvers(ViewResolverRegistry registry) {
    }

    /**
     * Add custom {@link org.springframework.web.servlet.HandlerInterceptor HandlerInterceptors}
     * to use in the framework.
     * @param registry the interceptor registry
     * @since 4.2
     */
    default void addInterceptors(InterceptorRegistry registry) {
    }

    /**
     * Configure resource handling for serving static resources.
     * @param registry the resource handler registry
     * @since 4.2
     */
    default void addResourceHandlers(ResourceHandlerRegistry registry) {
    }

    /**
     * Configure cross origin request processing.
     * @param registry the CORS registry
     * @since 4.2
     */
    default void addCorsMappings(CorsRegistry registry) {
    }

    /**
     * Helps with configuring the {@link org.springframework.format.FormatterRegistry}
     * such as adding formatters or converters.
     * @param registry the formatter registry
     * @since 4.2
     */
    default void addFormatters(FormatterRegistry registry) {
    }

}
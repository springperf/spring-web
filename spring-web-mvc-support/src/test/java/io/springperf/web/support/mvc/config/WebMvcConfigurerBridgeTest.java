package io.springperf.web.support.mvc.config;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.ArgumentResolverRegistry;
import io.springperf.web.core.arg.databinder.WebDataBinderRegistry;
import io.springperf.web.core.arg.provider.StaticArgumentResolverProvider;
import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.core.codec.HttpBodyCodecRegistry;
import io.springperf.web.core.codec.HttpBodyConverter;
import io.springperf.web.core.cors.CorsRegistry;
import io.springperf.web.core.exception.ExceptionRegistry;
import io.springperf.web.core.interceptor.InterceptorRegistry;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.resource.ResourceHandlerRegistry;
import io.springperf.web.core.retval.ReturnValueResolver;
import io.springperf.web.core.retval.ReturnValueResolverRegistry;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.format.FormatterRegistry;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.http.CacheControl;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.validation.Validator;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.ValidatorRegistration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebMvcConfigurerBridgeTest {

    @Mock(lenient = true)
    WebContext webContext;

    @Mock
    ApplicationContext applicationContext;

    @Captor
    ArgumentCaptor<Long> timeoutCaptor;

    WebMvcConfigurerBridge bridge;

    @BeforeEach
    void setUp() {
        when(webContext.getCtx()).thenReturn(applicationContext);
        // ResourceHandlerRegistry.initComponentPhase2 读取 spring.web.resources.* 与 static-path-pattern，
        // 统一兜底桩避免 mock 返回 null 触发 NPE。
        io.springperf.web.context.ApplicationProperties props = mock(
                io.springperf.web.context.ApplicationProperties.class, RETURNS_DEFAULTS);
        lenient().when(props.get(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(props.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(webContext.getProps()).thenReturn(props);
        bridge = new WebMvcConfigurerBridge();
        bridge.initWithWebContext(webContext);
    }

    // ---- No configurers ----

    @Test
    void noConfigurers_doesNothing() throws Exception {
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class)).thenReturn(Collections.emptyMap());

        bridge.initComponentPhase1();
        // no exception, no interactions with registries
    }

    // ---- Interceptors ----

    @Test
    void addInterceptor_registersWrappedInterceptor() throws Exception {
        InterceptorRegistry frameworkRegistry = new InterceptorRegistry();
        when(webContext.getWebComponent(InterceptorRegistry.class)).thenReturn(frameworkRegistry);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void addInterceptors(
                            org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
                        registry.addInterceptor(new TestHandlerInterceptor()).addPathPatterns("/api/**")
                                .excludePathPatterns("/api/public/**").order(5);
                    }
                }));

        bridge.initComponentPhase1();

        assertFalse(frameworkRegistry.getWebComponents(io.springperf.web.core.interceptor.InterceptorRegistration.class)
                .isEmpty(), "InterceptorRegistration should be registered");
    }

    @Test
    void addInterceptor_withoutPatterns_usesEmptyPatterns() throws Exception {
        InterceptorRegistry frameworkRegistry = new InterceptorRegistry();
        when(webContext.getWebComponent(InterceptorRegistry.class)).thenReturn(frameworkRegistry);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void addInterceptors(
                            org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
                        registry.addInterceptor(new TestHandlerInterceptor());
                    }
                }));

        bridge.initComponentPhase1();

        assertFalse(frameworkRegistry.getWebComponents(io.springperf.web.core.interceptor.InterceptorRegistration.class)
                .isEmpty());
    }

    @Test
    void addInterceptor_multipleConfigurers_aggregatesAll() throws Exception {
        InterceptorRegistry frameworkRegistry = new InterceptorRegistry();
        when(webContext.getWebComponent(InterceptorRegistry.class)).thenReturn(frameworkRegistry);

        java.util.LinkedHashMap<String, WebMvcConfigurer> configurers = new java.util.LinkedHashMap<>();
        configurers.put("c1", new WebMvcConfigurer() {
            @Override
            public void addInterceptors(
                    org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
                registry.addInterceptor(new TestHandlerInterceptor()).addPathPatterns("/api/**");
            }
        });
        configurers.put("c2", new WebMvcConfigurer() {
            @Override
            public void addInterceptors(
                    org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
                registry.addInterceptor(new TestHandlerInterceptor()).addPathPatterns("/admin/**");
            }
        });
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class)).thenReturn(configurers);

        bridge.initComponentPhase1();

        assertFalse(frameworkRegistry.getWebComponents(io.springperf.web.core.interceptor.InterceptorRegistration.class)
                .isEmpty());
    }

    @Test
    void addInterceptors_orderPreservedForDefaultOrder() throws Exception {
        // 用户未显式设置 order 的多个拦截器，桥接后应按添加顺序获得递增 order
        // （0, STEP, 2*STEP...），使框架侧稳定排序保持用户定义顺序。
        InterceptorRegistry frameworkRegistry = new InterceptorRegistry();
        when(webContext.getWebComponent(InterceptorRegistry.class)).thenReturn(frameworkRegistry);

        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void addInterceptors(
                            org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
                        registry.addInterceptor(new TestHandlerInterceptor());
                        registry.addInterceptor(new TestHandlerInterceptor());
                        registry.addInterceptor(new TestHandlerInterceptor());
                    }
                }));

        bridge.initComponentPhase1();

        List<io.springperf.web.core.interceptor.InterceptorRegistration> regs = frameworkRegistry
                .getWebComponents(io.springperf.web.core.interceptor.InterceptorRegistration.class);
        assertEquals(3, regs.size());
        int first = regs.get(0).getOrder();
        int second = regs.get(1).getOrder();
        int third = regs.get(2).getOrder();
        assertTrue(first < second && second < third, "默认 order 拦截器应按添加顺序递增: " + first + ", " + second + ", " + third);
        assertEquals(0, first);
        assertEquals(first + WebMvcConfigurerBridge.DEFAULT_INTERCEPTOR_ORDER_STEP, second);
        assertEquals(second + WebMvcConfigurerBridge.DEFAULT_INTERCEPTOR_ORDER_STEP, third);
    }

    @Test
    void addInterceptors_explicitOrderPreserved() throws Exception {
        // 显式设置 order 的拦截器应保留用户给定的 order 值（不参与默认序列分配），
        // framework registry 读取时按 order 升序返回。
        InterceptorRegistry frameworkRegistry = new InterceptorRegistry();
        when(webContext.getWebComponent(InterceptorRegistry.class)).thenReturn(frameworkRegistry);

        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void addInterceptors(
                            org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
                        registry.addInterceptor(new TestHandlerInterceptor()).order(50);
                        registry.addInterceptor(new TestHandlerInterceptor()).order(-10);
                    }
                }));

        bridge.initComponentPhase1();

        List<io.springperf.web.core.interceptor.InterceptorRegistration> regs = frameworkRegistry
                .getWebComponents(io.springperf.web.core.interceptor.InterceptorRegistration.class);
        assertEquals(2, regs.size());
        assertEquals(-10, regs.get(0).getOrder());
        assertEquals(50, regs.get(1).getOrder());
    }

    @Test
    void addInterceptors_mixedDefaultAndExplicitOrder() throws Exception {
        // 默认序与显式序混合：显式 order 透传，默认项按添序递增且落在显式值之外的区间
        InterceptorRegistry frameworkRegistry = new InterceptorRegistry();
        when(webContext.getWebComponent(InterceptorRegistry.class)).thenReturn(frameworkRegistry);

        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void addInterceptors(
                            org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
                        registry.addInterceptor(new TestHandlerInterceptor()); // default -> 0
                        registry.addInterceptor(new TestHandlerInterceptor()).order(10); // explicit 10
                        registry.addInterceptor(new TestHandlerInterceptor()); // default -> 100
                    }
                }));

        bridge.initComponentPhase1();

        List<io.springperf.web.core.interceptor.InterceptorRegistration> regs = frameworkRegistry
                .getWebComponents(io.springperf.web.core.interceptor.InterceptorRegistration.class);
        assertEquals(3, regs.size());
        assertEquals(0, regs.get(0).getOrder()); // 第一个默认项
        assertEquals(10, regs.get(1).getOrder()); // 显式序保留
        assertEquals(100, regs.get(2).getOrder()); // 第二个默认项 = 0 + 1*STEP
    }

    // ---- CORS ----

    @Test
    void addCorsMapping_registersInCorsRegistry() throws Exception {
        CorsRegistry frameworkRegistry = new CorsRegistry();
        when(webContext.getWebComponent(CorsRegistry.class)).thenReturn(frameworkRegistry);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void addCorsMappings(
                            org.springframework.web.servlet.config.annotation.CorsRegistry registry) {
                        registry.addMapping("/api/**").allowedOrigins("https://example.com")
                                .allowedMethods("GET", "POST").allowCredentials(true).maxAge(3600);
                    }
                }));

        bridge.initComponentPhase1();

        assertFalse(frameworkRegistry.getWebComponents(io.springperf.web.core.cors.CorsRegistration.class).isEmpty());
    }

    @Test
    void addCorsMapping_defaultValues_accepted() throws Exception {
        CorsRegistry frameworkRegistry = new CorsRegistry();
        when(webContext.getWebComponent(CorsRegistry.class)).thenReturn(frameworkRegistry);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void addCorsMappings(
                            org.springframework.web.servlet.config.annotation.CorsRegistry registry) {
                        registry.addMapping("/public/**");
                    }
                }));

        bridge.initComponentPhase1();

        assertFalse(frameworkRegistry.getWebComponents(io.springperf.web.core.cors.CorsRegistration.class).isEmpty());
    }

    // ---- Resource handlers ----

    @Test
    void addResourceHandler_registersInMappingRegistry() throws Exception {
        MappingRegistry mappingRegistry = new MappingRegistry();
        ResourceHandlerRegistry resourceHandlerRegistry = new ResourceHandlerRegistry();
        when(webContext.getWebComponent(MappingRegistry.class)).thenReturn(mappingRegistry);
        when(webContext.getWebComponentWithDefault(eq(MappingRegistry.class), any())).thenReturn(mappingRegistry);
        when(webContext.getWebComponent(ResourceHandlerRegistry.class)).thenReturn(resourceHandlerRegistry);
        resourceHandlerRegistry.initWithWebContext(webContext);
        resourceHandlerRegistry.initComponentPhase1();

        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void addResourceHandlers(
                            org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
                        registry.addResourceHandler("/static/**").addResourceLocations("classpath:/static/")
                                .setCachePeriod(3600).setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS));
                    }
                }));

        bridge.initComponentPhase1();

        // Phase 2 builds PathMappingContext from ResourceHandlerRegistration and registers into MappingRegistry
        resourceHandlerRegistry.initComponentPhase2();

        boolean hasResourcePath = mappingRegistry.getMappingContextList().stream()
                .anyMatch(ctx -> ctx.getPathRule().equals("/static/**"));
        assertTrue(hasResourcePath, "PathMappingContext for /static/** should be registered in MappingRegistry");
    }

    @Test
    void addResourceHandler_withoutLocations_usesEmptyLocations() throws Exception {
        MappingRegistry mappingRegistry = new MappingRegistry();
        ResourceHandlerRegistry resourceHandlerRegistry = new ResourceHandlerRegistry();
        when(webContext.getWebComponent(MappingRegistry.class)).thenReturn(mappingRegistry);
        when(webContext.getWebComponentWithDefault(eq(MappingRegistry.class), any())).thenReturn(mappingRegistry);
        when(webContext.getWebComponent(ResourceHandlerRegistry.class)).thenReturn(resourceHandlerRegistry);
        resourceHandlerRegistry.initWithWebContext(webContext);
        resourceHandlerRegistry.initComponentPhase1();

        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void addResourceHandlers(
                            org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
                        registry.addResourceHandler("/assets/**");
                    }
                }));

        bridge.initComponentPhase1();

        // Phase 2 builds PathMappingContext and registers into MappingRegistry
        resourceHandlerRegistry.initComponentPhase2();

        boolean hasResourcePath = mappingRegistry.getMappingContextList().stream()
                .anyMatch(ctx -> ctx.getPathRule().equals("/assets/**"));
        assertTrue(hasResourcePath, "PathMappingContext for /assets/** should be registered in MappingRegistry");
    }

    // ---- Formatters ----

    @Test
    void addFormatters_registersOnConversionService() throws Exception {
        DefaultFormattingConversionService conversionService = new DefaultFormattingConversionService();
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void addFormatters(FormatterRegistry registry) {
                        registry.addFormatterForFieldType(String.class,
                                new org.springframework.format.Formatter<String>() {
                                    @Override
                                    public String parse(String text, java.util.Locale locale) {
                                        return text.trim();
                                    }

                                    @Override
                                    public String print(String object, java.util.Locale locale) {
                                        return object;
                                    }
                                });
                    }
                }));
        doReturn(conversionService).when(webContext).getBeanFromCtx(ConversionService.class);

        bridge.initComponentPhase1();

        assertNotNull(conversionService);
    }

    @Test
    void addFormatters_nullConversionService_skipsGracefully() throws Exception {
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                }));
        doReturn(null).when(webContext).getBeanFromCtx(ConversionService.class);

        bridge.initComponentPhase1();
    }

    @Test
    void addFormatters_nonFormatterRegistryConversionService_skipsGracefully() throws Exception {
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                }));
        ConversionService plainCs = mock(ConversionService.class);
        doReturn(plainCs).when(webContext).getBeanFromCtx(ConversionService.class);

        bridge.initComponentPhase1();
    }

    // ---- Edge cases ----

    @Test
    void nullInterceptorRegistry_skipsInterceptorsGracefully() throws Exception {
        when(webContext.getWebComponent(InterceptorRegistry.class)).thenReturn(null);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void addInterceptors(
                            org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
                        registry.addInterceptor(new TestHandlerInterceptor());
                    }
                }));

        bridge.initComponentPhase1();
    }

    @Test
    void nullCorsRegistry_skipsCorsGracefully() throws Exception {
        when(webContext.getWebComponent(CorsRegistry.class)).thenReturn(null);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void addCorsMappings(
                            org.springframework.web.servlet.config.annotation.CorsRegistry registry) {
                        registry.addMapping("/api/**");
                    }
                }));

        bridge.initComponentPhase1();
    }

    @Test
    void nullResourceHandlerRegistry_skipsResourcesGracefully() throws Exception {
        when(webContext.getWebComponent(ResourceHandlerRegistry.class)).thenReturn(null);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void addResourceHandlers(
                            org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
                        registry.addResourceHandler("/static/**");
                    }
                }));

        bridge.initComponentPhase1();
    }

    @Test
    void nullAsyncSupportRegistry_skipsAsyncSupportGracefully() throws Exception {
        when(webContext.getWebComponent(AsyncSupportRegistry.class)).thenReturn(null);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                        configurer.setDefaultTimeout(5000);
                    }
                }));

        bridge.initComponentPhase1();
    }

    @Test
    void configureAsyncTimeout_bridgesToRegistry() throws Exception {
        AsyncSupportRegistry asyncRegistry = mock(AsyncSupportRegistry.class);
        when(webContext.getWebComponent(AsyncSupportRegistry.class)).thenReturn(asyncRegistry);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                        configurer.setDefaultTimeout(10000);
                    }
                }));

        bridge.initComponentPhase1();

        verify(asyncRegistry).setDefaultTimeout(10000L);
    }

    @Test
    void configureAsyncExecutor_bridgesToRegistry() throws Exception {
        AsyncSupportRegistry asyncRegistry = mock(AsyncSupportRegistry.class);
        when(webContext.getWebComponent(AsyncSupportRegistry.class)).thenReturn(asyncRegistry);
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                        configurer.setTaskExecutor(executor);
                    }
                }));

        bridge.initComponentPhase1();

        verify(asyncRegistry).setTaskExecutor(executor);
    }

    @Test
    void configureCallableInterceptors_bridgesToRegistry() throws Exception {
        AsyncSupportRegistry asyncRegistry = mock(AsyncSupportRegistry.class);
        when(webContext.getWebComponent(AsyncSupportRegistry.class)).thenReturn(asyncRegistry);
        CallableProcessingInterceptor interceptor = mock(CallableProcessingInterceptor.class);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                        configurer.registerCallableInterceptors(interceptor);
                    }
                }));

        bridge.initComponentPhase1();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<CallableProcessingInterceptor>> captor = ArgumentCaptor
                .forClass(java.util.List.class);
        verify(asyncRegistry).addCallableInterceptors(captor.capture());
        assertTrue(captor.getValue().contains(interceptor));
    }

    @Test
    void configureDeferredResultInterceptors_bridgesToRegistry() throws Exception {
        AsyncSupportRegistry asyncRegistry = mock(AsyncSupportRegistry.class);
        when(webContext.getWebComponent(AsyncSupportRegistry.class)).thenReturn(asyncRegistry);
        DeferredResultProcessingInterceptor interceptor = mock(DeferredResultProcessingInterceptor.class);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                        configurer.registerDeferredResultInterceptors(interceptor);
                    }
                }));

        bridge.initComponentPhase1();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<DeferredResultProcessingInterceptor>> captor = ArgumentCaptor
                .forClass(java.util.List.class);
        verify(asyncRegistry).addDeferredResultInterceptors(captor.capture());
        assertTrue(captor.getValue().contains(interceptor));
    }

    // ========== New bridge method tests: addArgumentResolvers ==========

    @Test
    void addArgumentResolver_registersAdapterOnRegistry() throws Exception {
        ArgumentResolverRegistry argRegistry = new ArgumentResolverRegistry();
        when(webContext.getWebComponent(ArgumentResolverRegistry.class)).thenReturn(argRegistry);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                        resolvers.add(new HandlerMethodArgumentResolver() {
                            @Override
                            public boolean supportsParameter(MethodParameter parameter) {
                                return parameter.getParameterType() == String.class;
                            }

                            @Override
                            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                    NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                                return "resolved";
                            }
                        });
                    }
                }));

        bridge.initComponentPhase1();

        assertFalse(argRegistry.getWebComponents(StaticArgumentResolverProvider.class).isEmpty());
    }

    @Test
    void addArgumentResolver_nullRegistry_skipsGracefully() throws Exception {
        doReturn(null).when(webContext).getWebComponent(ArgumentResolverRegistry.class);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                        resolvers.add(mock(HandlerMethodArgumentResolver.class));
                    }
                }));

        bridge.initComponentPhase1();
        // no exception
    }

    @Test
    void addArgumentResolver_emptyResolvers_doesNothing() throws Exception {
        ArgumentResolverRegistry argRegistry = new ArgumentResolverRegistry();
        when(webContext.getWebComponent(ArgumentResolverRegistry.class)).thenReturn(argRegistry);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                        // no resolvers added
                    }
                }));

        bridge.initComponentPhase1();

        assertTrue(argRegistry.getWebComponents(StaticArgumentResolverProvider.class).isEmpty());
    }

    // ========== New bridge method tests: extendMessageConverters ==========

    @Test
    void extendMessageConverters_registersWrapperOnCodecRegistry() throws Exception {
        HttpBodyCodecRegistry codecRegistry = new HttpBodyCodecRegistry();
        doReturn(codecRegistry).when(webContext).getWebComponent(HttpBodyCodecRegistry.class);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                        converters.add(new StringHttpMessageConverter());
                    }
                }));

        bridge.initComponentPhase1();

        assertFalse(codecRegistry.getWebComponents(HttpBodyConverter.class).isEmpty());
    }

    @Test
    void extendMessageConverters_nullRegistry_skipsGracefully() throws Exception {
        doReturn(null).when(webContext).getWebComponent(HttpBodyCodecRegistry.class);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                        converters.add(new StringHttpMessageConverter());
                    }
                }));

        bridge.initComponentPhase1();
        // no exception
    }

    @Test
    void extendMessageConverters_multipleConfigurers_aggregatesAll() throws Exception {
        HttpBodyCodecRegistry codecRegistry = new HttpBodyCodecRegistry();
        doReturn(codecRegistry).when(webContext).getWebComponent(HttpBodyCodecRegistry.class);

        java.util.LinkedHashMap<String, WebMvcConfigurer> configurers = new java.util.LinkedHashMap<>();
        configurers.put("c1", new WebMvcConfigurer() {
            @Override
            public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                converters.add(new StringHttpMessageConverter());
            }
        });
        configurers.put("c2", new WebMvcConfigurer() {
            @Override
            public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                converters.add(new org.springframework.http.converter.ByteArrayHttpMessageConverter());
            }
        });
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class)).thenReturn(configurers);

        bridge.initComponentPhase1();

        assertEquals(2, codecRegistry.getWebComponents(HttpBodyConverter.class).size());
    }

    // ========== New bridge method tests: addReturnValueHandlers ==========

    @Test
    void addReturnValueHandler_registersAdapterOnRegistry() throws Exception {
        ReturnValueResolverRegistry retValRegistry = new ReturnValueResolverRegistry();
        doReturn(retValRegistry).when(webContext).getWebComponent(ReturnValueResolverRegistry.class);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void addReturnValueHandlers(List<HandlerMethodReturnValueHandler> handlers) {
                        handlers.add(new HandlerMethodReturnValueHandler() {
                            @Override
                            public boolean supportsReturnType(MethodParameter returnType) {
                                return returnType.getParameterType() == String.class;
                            }

                            @Override
                            public void handleReturnValue(Object returnValue, MethodParameter returnType,
                                    ModelAndViewContainer mavContainer, NativeWebRequest webRequest) {
                            }
                        });
                    }
                }));

        bridge.initComponentPhase1();

        assertFalse(retValRegistry.getWebComponents(ReturnValueResolver.class).isEmpty());
    }

    @Test
    void addReturnValueHandler_nullRegistry_skipsGracefully() throws Exception {
        doReturn(null).when(webContext).getWebComponent(ReturnValueResolverRegistry.class);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void addReturnValueHandlers(List<HandlerMethodReturnValueHandler> handlers) {
                        handlers.add(mock(HandlerMethodReturnValueHandler.class));
                    }
                }));

        bridge.initComponentPhase1();
        // no exception
    }

    @Test
    void addReturnValueHandler_emptyHandlers_doesNothing() throws Exception {
        ReturnValueResolverRegistry retValRegistry = new ReturnValueResolverRegistry();
        doReturn(retValRegistry).when(webContext).getWebComponent(ReturnValueResolverRegistry.class);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                }));

        bridge.initComponentPhase1();

        assertTrue(retValRegistry.getWebComponents(ReturnValueResolver.class).isEmpty());
    }

    // ========== New bridge method tests: extendHandlerExceptionResolvers ==========

    @Test
    void extendHandlerExceptionResolvers_registersAdapterOnRegistry() throws Exception {
        ExceptionRegistry exRegistry = new ExceptionRegistry();
        doReturn(exRegistry).when(webContext).getWebComponent(ExceptionRegistry.class);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
                        resolvers.add((request, response, handler, ex) -> null);
                    }
                }));

        bridge.initComponentPhase1();

        assertFalse(
                exRegistry.getWebComponents(io.springperf.web.core.exception.HandlerExceptionResolver.class).isEmpty());
    }

    @Test
    void extendHandlerExceptionResolvers_nullRegistry_skipsGracefully() throws Exception {
        doReturn(null).when(webContext).getWebComponent(ExceptionRegistry.class);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
                        resolvers.add((request, response, handler, ex) -> null);
                    }
                }));

        bridge.initComponentPhase1();
        // no exception
    }

    @Test
    void extendHandlerExceptionResolvers_emptyResolvers_doesNothing() throws Exception {
        ExceptionRegistry exRegistry = spy(new ExceptionRegistry());
        doReturn(exRegistry).when(webContext).getWebComponent(ExceptionRegistry.class);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                }));

        bridge.initComponentPhase1();

        assertTrue(
                exRegistry.getWebComponents(io.springperf.web.core.exception.HandlerExceptionResolver.class).isEmpty());
    }

    // ========== New bridge method tests: configureValidator ==========

    @Test
    void configureValidator_setsOnDataBinderRegistry() throws Exception {
        WebDataBinderRegistry binderRegistry = spy(new WebDataBinderRegistry());
        ArgumentResolverRegistry argRegistry = mock(ArgumentResolverRegistry.class);
        doReturn(binderRegistry).when(argRegistry).getWebDataBinderRegistry();
        doReturn(argRegistry).when(webContext).getWebComponent(ArgumentResolverRegistry.class);
        Validator mockValidator = mock(Validator.class);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void configureValidator(ValidatorRegistration registration) {
                        registration.validator(mockValidator);
                    }
                }));

        bridge.initComponentPhase1();

        verify(binderRegistry).setDefaultValidator(mockValidator);
    }

    @Test
    void configureValidator_nullRegistry_skipsGracefully() throws Exception {
        doReturn(null).when(webContext).getWebComponent(ArgumentResolverRegistry.class);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    @Override
                    public void configureValidator(ValidatorRegistration registration) {
                        registration.validator(mock(Validator.class));
                    }
                }));

        bridge.initComponentPhase1();
        // no exception
    }

    @Test
    void configureValidator_noValidatorSet_doesNotCallSetter() throws Exception {
        WebDataBinderRegistry binderRegistry = spy(new WebDataBinderRegistry());
        ArgumentResolverRegistry argRegistry = mock(ArgumentResolverRegistry.class);
        doReturn(binderRegistry).when(argRegistry).getWebDataBinderRegistry();
        doReturn(argRegistry).when(webContext).getWebComponent(ArgumentResolverRegistry.class);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("test", new WebMvcConfigurer() {
                    // configureValidator not overridden — default no-op
                }));

        bridge.initComponentPhase1();

        verify(binderRegistry, never()).setDefaultValidator(any());
    }

    @Test
    void configureValidator_multipleConfigurers_lastWins() throws Exception {
        WebDataBinderRegistry binderRegistry = spy(new WebDataBinderRegistry());
        ArgumentResolverRegistry argRegistry = mock(ArgumentResolverRegistry.class);
        doReturn(binderRegistry).when(argRegistry).getWebDataBinderRegistry();
        doReturn(argRegistry).when(webContext).getWebComponent(ArgumentResolverRegistry.class);
        Validator firstValidator = mock(Validator.class);
        Validator secondValidator = mock(Validator.class);
        java.util.LinkedHashMap<String, WebMvcConfigurer> configurers = new java.util.LinkedHashMap<>();
        configurers.put("c1", new WebMvcConfigurer() {
            @Override
            public void configureValidator(ValidatorRegistration registration) {
                registration.validator(firstValidator);
            }
        });
        configurers.put("c2", new WebMvcConfigurer() {
            @Override
            public void configureValidator(ValidatorRegistration registration) {
                registration.validator(secondValidator);
            }
        });
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class)).thenReturn(configurers);

        bridge.initComponentPhase1();

        verify(binderRegistry).setDefaultValidator(secondValidator);
    }

    // ---- Helper ----

    static class TestHandlerInterceptor implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            return true;
        }
    }

    // ========== 未桥接回调的 WARN 检测 ==========

    @Test
    void unsupportedCallbacks_constantCoversExpectedMethods() {
        // 7 个未桥接回调均应被登记（覆写即静默失效，需 WARN）；addViewControllers 已桥接，不再登记
        assertTrue(WebMvcConfigurerBridge.UNSUPPORTED_CALLBACKS.containsKey("configurePathMatch"));
        assertTrue(WebMvcConfigurerBridge.UNSUPPORTED_CALLBACKS.containsKey("configureContentNegotiation"));
        assertTrue(WebMvcConfigurerBridge.UNSUPPORTED_CALLBACKS.containsKey("configureDefaultServletHandling"));
        assertTrue(WebMvcConfigurerBridge.UNSUPPORTED_CALLBACKS.containsKey("configureHandlerExceptionResolvers"));
        assertTrue(WebMvcConfigurerBridge.UNSUPPORTED_CALLBACKS.containsKey("configureMessageCodesResolver"));
        assertTrue(WebMvcConfigurerBridge.UNSUPPORTED_CALLBACKS.containsKey("configureMessageConverters"));
        assertTrue(WebMvcConfigurerBridge.UNSUPPORTED_CALLBACKS.containsKey("configureViewResolvers"));
        assertFalse(WebMvcConfigurerBridge.UNSUPPORTED_CALLBACKS.containsKey("addViewControllers"),
                "addViewControllers 已桥接，不应再出现在未桥接清单");
        assertEquals(7, WebMvcConfigurerBridge.UNSUPPORTED_CALLBACKS.size());
    }

    @Test
    void warnUnsupportedOverrides_overriddenCallback_emitsWarnWithoutError() throws Exception {
        // 覆写未桥接回调：initComponentPhase1 仍应正常完成（仅 WARN），不得抛异常
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("viewCfg", new WebMvcConfigurer() {
                    @Override
                    public void addViewControllers(
                            org.springframework.web.servlet.config.annotation.ViewControllerRegistry registry) {
                        // 用户以为生效，实际框架未桥接 → 应 WARN
                    }
                }));

        assertDoesNotThrow(() -> bridge.initComponentPhase1());
    }

    @Test
    void warnUnsupportedOverrides_overriddenPathMatch_emitsWarnWithoutError() throws Exception {
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("pathCfg", new WebMvcConfigurer() {
                    @Override
                    public void configurePathMatch(
                            org.springframework.web.servlet.config.annotation.PathMatchConfigurer configurer) {
                        // 未桥接
                    }
                }));

        assertDoesNotThrow(() -> bridge.initComponentPhase1());
    }

    @Test
    void warnUnsupportedOverrides_noOverride_noWarnNoError() throws Exception {
        // 空实现（未覆写任何未桥接回调）：不应告警，也不应抛异常
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("empty", new WebMvcConfigurer() {
                }));

        assertDoesNotThrow(() -> bridge.initComponentPhase1());
    }

    @Test
    void warnUnsupportedOverrides_supportedCallback_doesNotThrow() throws Exception {
        // 覆写「已桥接」回调（addInterceptors）：捕获器行为正常，且不应触发未桥接 WARN 路径异常
        InterceptorRegistry frameworkRegistry = new InterceptorRegistry();
        doReturn(frameworkRegistry).when(webContext).getWebComponent(InterceptorRegistry.class);
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("ok", new WebMvcConfigurer() {
                    @Override
                    public void addInterceptors(
                            org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
                        registry.addInterceptor(new TestHandlerInterceptor());
                    }
                }));

        assertDoesNotThrow(() -> bridge.initComponentPhase1());
    }

    // ========== addViewControllers 桥接 ==========

    @Test
    void viewControllerRegistry_shim_collectsRegistrations() {
        org.springframework.web.servlet.config.annotation.ViewControllerRegistry registry = new org.springframework.web.servlet.config.annotation.ViewControllerRegistry();
        registry.addViewController("/home").setViewName("index");
        registry.addRedirectViewController("/old", "/new");
        registry.addStatusController("/ping", org.springframework.http.HttpStatus.NO_CONTENT);

        assertEquals(3, registry.getRegistrations().size());
        org.springframework.web.servlet.config.annotation.ViewControllerRegistration home = registry.getRegistrations()
                .get(0);
        assertEquals("/home", home.getUrlPath());
        assertEquals("index", home.getViewName());
        org.springframework.web.servlet.config.annotation.ViewControllerRegistration redirect = registry
                .getRegistrations().get(1);
        assertEquals("redirect:/new", redirect.getViewName(), "redirect 注册应带 redirect: 前缀");
        org.springframework.web.servlet.config.annotation.ViewControllerRegistration status = registry
                .getRegistrations().get(2);
        assertEquals(org.springframework.http.HttpStatus.NO_CONTENT, status.getStatusCode());
        assertNull(status.getViewName());
    }

    @Test
    void bridgeViewControllers_registersFrameworkMappings() throws Exception {
        MappingRegistry mappingRegistry = new MappingRegistry();
        doReturn(mappingRegistry).when(webContext).getWebComponentWithDefault(eq(MappingRegistry.class),
                any(MappingRegistry.class));
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("vc", new WebMvcConfigurer() {
                    @Override
                    public void addViewControllers(
                            org.springframework.web.servlet.config.annotation.ViewControllerRegistry registry) {
                        registry.addViewController("/home").setViewName("index");
                        registry.addStatusController("/ping", org.springframework.http.HttpStatus.NO_CONTENT);
                    }
                }));

        bridge.initComponentPhase1();

        boolean hasHome = mappingRegistry.getMappingContextList().stream()
                .anyMatch(ctx -> ctx.getPathRule().equals("/home"));
        boolean hasPing = mappingRegistry.getMappingContextList().stream()
                .anyMatch(ctx -> ctx.getPathRule().equals("/ping"));
        assertTrue(hasHome, "view controller /home 应注册为框架路由");
        assertTrue(hasPing, "status controller /ping 应注册为框架路由");
    }

    @Test
    void bridgeViewControllers_noRegistrations_noMappings() throws Exception {
        MappingRegistry mappingRegistry = new MappingRegistry();
        doReturn(mappingRegistry).when(webContext).getWebComponentWithDefault(eq(MappingRegistry.class),
                any(MappingRegistry.class));
        when(applicationContext.getBeansOfType(WebMvcConfigurer.class))
                .thenReturn(Collections.singletonMap("empty", new WebMvcConfigurer() {
                }));

        bridge.initComponentPhase1();

        assertTrue(mappingRegistry.getMappingContextList().isEmpty());
    }

    /** 构造带 RequestContext 桩的请求（ModelContext.getOrCreate 需要读写字节属性）；lenient 避免未用桩报错。 */
    private WebServerHttpRequest requestWithContext() {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        io.springperf.web.http.RequestContext reqCtx = mock(io.springperf.web.http.RequestContext.class);
        java.util.Map<io.springperf.web.http.RequestAttribute<?>, Object> attrs = new java.util.HashMap<>();
        lenient().doAnswer(inv -> attrs.get(inv.getArgument(0))).when(reqCtx)
                .getAttribute(any(io.springperf.web.http.RequestAttribute.class));
        lenient().doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(reqCtx).setAttribute(any(io.springperf.web.http.RequestAttribute.class), any());
        lenient().doReturn(reqCtx).when(req).getRequestContext();
        // RedirectView.buildLocation 读取请求所属 WebContext 的 contextPath
        lenient().doReturn(webContext).when(req).getWebContext();
        lenient().when(webContext.getContextPath()).thenReturn("");
        return req;
    }

    @Test
    void viewControllerInvoker_statusOnly_setsStatusCodeAndHandled() throws Throwable {
        ViewControllerInvoker invoker = new ViewControllerInvoker(webContext, null,
                org.springframework.http.HttpStatus.NO_CONTENT);
        WebServerHttpRequest req = requestWithContext();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

        invoker.invoke(new Object[] { req, resp });

        verify(resp).setStatusCode(org.springframework.http.HttpStatus.NO_CONTENT);
        verify(resp).setHandled();
    }

    @Test
    void viewControllerInvoker_redirect_setsLocation() throws Throwable {
        ViewControllerInvoker invoker = new ViewControllerInvoker(webContext, "redirect:/new", null);
        WebServerHttpRequest req = requestWithContext();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getHeaders()).thenReturn(new org.springframework.http.HttpHeaders());

        invoker.invoke(new Object[] { req, resp });

        verify(resp).setStatusCode(org.springframework.http.HttpStatus.FOUND);
        verify(resp).setHandled();
    }

    @Test
    void viewControllerInvoker_viewName_unresolvable_throws() throws Throwable {
        ViewControllerInvoker invoker = new ViewControllerInvoker(webContext, "no-such-view", null);
        WebServerHttpRequest req = requestWithContext();
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        lenient().when(resp.getHeaders()).thenReturn(new org.springframework.http.HttpHeaders());
        // 空的 ViewResolverRegistry：解析失败
        doReturn(new io.springperf.web.view.ViewResolverRegistry()).when(webContext).getWebComponentWithDefault(
                eq(io.springperf.web.view.ViewResolverRegistry.class),
                any(io.springperf.web.view.ViewResolverRegistry.class));

        // 无 ViewResolver：解析失败应抛异常（交 ExceptionRegistry 转 500），而非静默 200
        assertThrows(IllegalArgumentException.class, () -> invoker.invoke(new Object[] { req, resp }));
    }
}

package io.springperf.web.core.cors;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.cors.provider.CorsConfigurationProvider;
import io.springperf.web.core.cors.provider.NoneCorsConfigurationProvider;
import io.springperf.web.core.cors.provider.RuntimeMappingCorsConfigurationProvider;
import io.springperf.web.core.cors.provider.SimpleCorsConfigurationProvider;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.match.HttpMethodMatcher;
import io.springperf.web.core.mapping.match.Matcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.method.HandlerMethod;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CorsRegistryProviderTest {

    @CrossOrigin(origins = "http://a.com")
    static class ClassLevelController {
        @RequestMapping("/c")
        public String hello() { return "hello"; }
    }

    static class MethodLevelController {
        @CrossOrigin(origins = "http://b.com", methods = {org.springframework.web.bind.annotation.RequestMethod.PUT})
        @RequestMapping("/m")
        public String hello() { return "hello"; }
    }

    static class NoAnnotationController {
        @RequestMapping("/n")
        public String hello() { return "hello"; }
    }

    static class AllCredentialsController {
        @CrossOrigin(origins = "http://c.com", allowCredentials = "true")
        @RequestMapping("/ac")
        public String hello() { return "hello"; }
    }

    private WebContext webContext;
    private CorsRegistry registry;

    @BeforeEach
    void setUp() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(PropertiesConstant.CONTEXT_PATH, "/")).thenReturn("/");
        webContext = new WebContext(mock(DispatcherHandler.class), props);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(any())).thenReturn(Collections.emptyMap());
        webContext.setCtx(ctx);
        registry = new CorsRegistry();
        registry.initWithWebContext(webContext);
    }

    private PathMappingContext contextFor(Object controller, String pathRule, List<Matcher> matchers) throws Exception {
        HandlerMethod hm = new HandlerMethod(controller, controller.getClass().getMethod("hello"));
        return new PathMappingContext(hm, matchers, pathRule);
    }

    @Test
    void noCrossOrigin_noRegistrations_returnsNoneProvider() throws Exception {
        PathMappingContext ctx = contextFor(new NoAnnotationController(), "/n", Collections.emptyList());
        CorsConfigurationProvider provider = registry.createCorsConfigurationProvider(ctx);
        assertTrue(provider instanceof NoneCorsConfigurationProvider);
        assertNull(provider.getCorsConfiguration(mock(io.springperf.web.http.WebServerHttpRequest.class),
                mock(io.springperf.web.http.WebServerHttpResponse.class)));
    }

    @Test
    void classLevelCrossOrigin_returnsSimpleProviderWithConfig() throws Exception {
        PathMappingContext ctx = contextFor(new ClassLevelController(), "/c", Collections.emptyList());
        CorsConfigurationProvider provider = registry.createCorsConfigurationProvider(ctx);
        assertTrue(provider instanceof SimpleCorsConfigurationProvider);
        CorsConfiguration config = provider.getCorsConfiguration(
                mock(io.springperf.web.http.WebServerHttpRequest.class),
                mock(io.springperf.web.http.WebServerHttpResponse.class));
        assertNotNull(config);
        assertTrue(config.getAllowedOrigins().contains("http://a.com"));
    }

    @Test
    void methodLevelCrossOrigin_mergesConfigAndUsesHttpMethodMatcher() throws Exception {
        PathMappingContext ctx = contextFor(new MethodLevelController(), "/m",
                Collections.singletonList(new HttpMethodMatcher(new HttpMethod[]{HttpMethod.PUT})));
        CorsConfigurationProvider provider = registry.createCorsConfigurationProvider(ctx);
        CorsConfiguration config = provider.getCorsConfiguration(
                mock(io.springperf.web.http.WebServerHttpRequest.class),
                mock(io.springperf.web.http.WebServerHttpResponse.class));
        assertNotNull(config);
        assertTrue(config.getAllowedOrigins().contains("http://b.com"));
        assertTrue(config.getAllowedMethods().contains("PUT"));
    }

    @Test
    void allowCredentialsTrue_configApplied() throws Exception {
        PathMappingContext ctx = contextFor(new AllCredentialsController(), "/ac", Collections.emptyList());
        CorsConfigurationProvider provider = registry.createCorsConfigurationProvider(ctx);
        CorsConfiguration config = provider.getCorsConfiguration(
                mock(io.springperf.web.http.WebServerHttpRequest.class),
                mock(io.springperf.web.http.WebServerHttpResponse.class));
        assertNotNull(config);
        assertTrue(config.getAllowCredentials());
    }

    @Test
    void invalidAllowCredentials_throwsIllegalState() throws Exception {
        @CrossOrigin(allowCredentials = "maybe")
        class BadController {
            @RequestMapping("/bad")
            public String hello() { return "x"; }
        }
        PathMappingContext ctx = contextFor(new BadController(), "/bad", Collections.emptyList());
        assertThrows(IllegalStateException.class, () -> registry.createCorsConfigurationProvider(ctx));
    }

    @Test
    void withAlwaysRegistration_returnsCombinedSimpleProvider() throws Exception {
        CorsRegistry second = new CorsRegistry();
        second.addMapping("/api/**").allowedOrigins("http://x.com");
        second.initWithWebContext(webContext);
        second.initComponentPhase1();
        second.initComponentPhase2();

        PathMappingContext ctx = contextFor(new NoAnnotationController(), "/api/users", Collections.emptyList());
        CorsConfigurationProvider provider = second.createCorsConfigurationProvider(ctx);
        assertTrue(provider instanceof SimpleCorsConfigurationProvider);
        CorsConfiguration config = provider.getCorsConfiguration(
                mock(io.springperf.web.http.WebServerHttpRequest.class),
                mock(io.springperf.web.http.WebServerHttpResponse.class));
        assertNotNull(config);
        assertTrue(config.getAllowedOrigins().contains("http://x.com"));
    }

    @Test
    void withRuntimeRegistration_returnsRuntimeProvider() throws Exception {
        CorsRegistry second = new CorsRegistry();
        second.addMapping("/{id:\\d+}").allowedOrigins("http://x.com");
        second.initWithWebContext(webContext);
        second.initComponentPhase1();
        second.initComponentPhase2();

        PathMappingContext ctx = contextFor(new NoAnnotationController(), "/*", Collections.emptyList());
        CorsConfigurationProvider provider = second.createCorsConfigurationProvider(ctx);
        assertTrue(provider instanceof RuntimeMappingCorsConfigurationProvider,
                "RUNTIME 匹配注册应返回 RuntimeMappingCorsConfigurationProvider");
    }

    @Test
    void withNonMatchingRegistrations_noCrossOrigin_returnsNone() throws Exception {
        CorsRegistry second = new CorsRegistry();
        second.addMapping("/admin/**").allowedOrigins("http://x.com");
        second.initWithWebContext(webContext);
        second.initComponentPhase1();
        second.initComponentPhase2();

        PathMappingContext ctx = contextFor(new NoAnnotationController(), "/api/users", Collections.emptyList());
        CorsConfigurationProvider provider = second.createCorsConfigurationProvider(ctx);
        assertTrue(provider instanceof NoneCorsConfigurationProvider);
    }

    @Test
    void withNonMatchingRegistrations_hasCrossOrigin_returnsSimple() throws Exception {
        CorsRegistry second = new CorsRegistry();
        second.addMapping("/admin/**").allowedOrigins("http://x.com");
        second.initWithWebContext(webContext);
        second.initComponentPhase1();
        second.initComponentPhase2();

        PathMappingContext ctx = contextFor(new ClassLevelController(), "/c", Collections.emptyList());
        CorsConfigurationProvider provider = second.createCorsConfigurationProvider(ctx);
        assertTrue(provider instanceof SimpleCorsConfigurationProvider);
    }

    @Test
    void embeddedValueResolver_resolvesPlaceholders() throws Exception {
        registry.setEmbeddedValueResolver(value ->
                value.equals("${origin}") ? "http://resolved.com" : value);
        @CrossOrigin(origins = "${origin}")
        class PlaceholderController {
            @RequestMapping("/p")
            public String hello() { return "x"; }
        }
        PathMappingContext ctx = contextFor(new PlaceholderController(), "/p", Collections.emptyList());
        CorsConfigurationProvider provider = registry.createCorsConfigurationProvider(ctx);
        CorsConfiguration config = provider.getCorsConfiguration(
                mock(io.springperf.web.http.WebServerHttpRequest.class),
                mock(io.springperf.web.http.WebServerHttpResponse.class));
        assertNotNull(config);
        assertTrue(config.getAllowedOrigins().contains("http://resolved.com"));
    }

    /* ==================== getCorsConfigurationProvider 全路径 ==================== */

    private io.springperf.web.http.WebServerHttpRequest requestWithMapping(
            io.springperf.web.core.mapping.MappingResult result) {
        io.springperf.web.http.WebServerHttpRequest request =
                mock(io.springperf.web.http.WebServerHttpRequest.class);
        io.springperf.web.http.RequestContext requestContext =
                mock(io.springperf.web.http.RequestContext.class);
        java.util.Map<io.springperf.web.http.RequestAttribute<?>, Object> attrs = new java.util.HashMap<>();
        when(requestContext.getAttribute(any(io.springperf.web.http.RequestAttribute.class)))
                .thenAnswer(inv -> attrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(requestContext).setAttribute(any(io.springperf.web.http.RequestAttribute.class), any());
        when(request.getRequestContext()).thenReturn(requestContext);
        if (result != null) {
            io.springperf.web.core.mapping.MappingResult.set(request, result);
        }
        return request;
    }

    @Test
    void getCorsConfiguration_noMappingResult_returnsDefaultProvider() throws Exception {
        io.springperf.web.http.WebServerHttpRequest request = requestWithMapping(null);
        CorsConfiguration config = registry.getCorsConfiguration(request,
                mock(io.springperf.web.http.WebServerHttpResponse.class));
        assertNull(config, "无 MappingResult 时应走 NoneCorsConfigurationProvider");
    }

    @Test
    void getCorsConfiguration_matchedResult_usesMatchedContext() throws Exception {
        PathMappingContext ctx = contextFor(new ClassLevelController(), "/c", Collections.emptyList());
        io.springperf.web.http.WebServerHttpRequest request =
                requestWithMapping(io.springperf.web.core.mapping.MappingResult.matched(ctx));

        CorsConfiguration config = registry.getCorsConfiguration(request,
                mock(io.springperf.web.http.WebServerHttpResponse.class));

        assertNotNull(config, "matched 请求应按 @CrossOrigin 生成配置");
        assertTrue(config.getAllowedOrigins().contains("http://a.com"));
    }

    @Test
    void getCorsConfiguration_pathMatchedResult_usesFirstContext() throws Exception {
        PathMappingContext ctx = contextFor(new MethodLevelController(), "/m", Collections.emptyList());
        io.springperf.web.http.WebServerHttpRequest request =
                requestWithMapping(io.springperf.web.core.mapping.MappingResult.pathMatched(
                        new PathMappingContext[]{ctx}, true));

        CorsConfiguration config = registry.getCorsConfiguration(request,
                mock(io.springperf.web.http.WebServerHttpResponse.class));

        assertNotNull(config, "pathMatched 请求应按首个 context 生成配置");
        assertTrue(config.getAllowedOrigins().contains("http://b.com"));
    }

    @Test
    void getCorsConfiguration_notFoundResult_returnsDefaultProvider() throws Exception {
        io.springperf.web.http.WebServerHttpRequest request =
                requestWithMapping(io.springperf.web.core.mapping.MappingResult.notFound());

        CorsConfiguration config = registry.getCorsConfiguration(request,
                mock(io.springperf.web.http.WebServerHttpResponse.class));

        assertNull(config, "notFound 请求应走 NoneCorsConfigurationProvider");
    }
}

package io.springperf.web.core.cors;

import io.springperf.web.context.WebComponentContainer;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.cors.provider.CorsConfigurationProvider;
import io.springperf.web.core.cors.provider.NoneCorsConfigurationProvider;
import io.springperf.web.core.cors.provider.RuntimeMappingCorsConfigurationProvider;
import io.springperf.web.core.cors.provider.SimpleCorsConfigurationProvider;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.mapping.match.HttpMethodMatcher;
import io.springperf.web.core.mapping.match.Matcher;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.util.support.ContainmentResult;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpMethod;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringValueResolver;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.cors.CorsConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages CORS configuration by aggregating @CrossOrigin annotations and programmatic registrations, then resolving the effective configuration per request.
 */
public class CorsRegistry extends WebComponentContainer implements EmbeddedValueResolverAware {


    private static final CorsConfigurationProvider DEFAULT_CORS_CONFIGURATION_PROVIDER = new NoneCorsConfigurationProvider();

    private final List<CorsRegistration> registrations = new ArrayList<>();

    private WebCorsProcessor webCorsProcessor;

    @Nullable
    private StringValueResolver embeddedValueResolver;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        this.webCorsProcessor = webContext.getWebComponentWithDefault(WebCorsProcessor.class, new PerfCorsProcessor());
        registerWebComponent(CorsRegistration.class);
    }

    @Override
    public void initComponentPhase2() throws Exception {
        super.initComponentPhase2();
        initRealComponentList(registrations, CorsRegistration.class);
    }

    public CorsRegistration addMapping(String pathPattern) {
        CorsRegistration registration = new CorsRegistration(pathPattern);
        registerWebComponent(registration);
        return registration;
    }

    /**
     * 在初始化完成后注册预构建的 CORS 配置。
     * <p>适用于框架初始化后动态添加路由 CORS 配置的场景（如 Actuator 端点）。
     * 与 {@link #addMapping(String)} 不同，该方法会直接写入已构建的 registrations 列表，
     * 因为 {@link #initComponentPhase1()} 已将列表固化。</p>
     *
     * @param pathPattern       the path pattern to match
     * @param corsConfiguration the CORS configuration
     */
    public synchronized void addActuatorCorsConfiguration(String pathPattern, CorsConfiguration corsConfiguration) {
        CorsRegistration registration = new CorsRegistration(pathPattern, corsConfiguration);
        registerWebComponent(registration);
        this.registrations.add(registration);
    }

    public boolean corsHandle(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        CorsConfigurationProvider provider = getCorsConfigurationProvider(request);
        CorsConfiguration config = provider.getCorsConfiguration(request, response);
        boolean preFlightRequest = CorsUtils.isPreFlightRequest(request);
        if (config == null && !preFlightRequest) {
            return false;
        }
        if (preFlightRequest) {
            webCorsProcessor.process(config, request, response);
            return true;
        } else {
            return !webCorsProcessor.process(config, request, response);
        }
    }


    public CorsConfiguration getCorsConfiguration(WebServerHttpRequest request, WebServerHttpResponse response) {
        CorsConfigurationProvider provider = getCorsConfigurationProvider(request);
        return provider.getCorsConfiguration(request, response);
    }

    protected CorsConfigurationProvider getCorsConfigurationProvider(WebServerHttpRequest request) {
        MappingResult mr = MappingResult.get(request);
        if (mr == null) {
            return DEFAULT_CORS_CONFIGURATION_PROVIDER;
        }
        PathMappingContext context;
        if(mr.isMatched()){
            context = mr.getMatchedContext();
        }else if(mr.isPathMatched()){
            context = mr.getPathMatchedContexts()[0];
        }else{
            context = null;
        }
        if (context == null) {
            return DEFAULT_CORS_CONFIGURATION_PROVIDER;
        }
        CorsConfigurationProvider provider = context.getCorsConfigurationProvider();
        if (provider != null) {
            return provider;
        }
        provider = createCorsConfigurationProvider(context);
        context.setCorsConfigurationProvider(provider);
        return provider;
    }

    protected CorsConfigurationProvider createCorsConfigurationProvider(PathMappingContext context) {
        boolean haveCrossOrigin = context.getMethodAndClassAnnotation(CrossOrigin.class) != null;
        if (!haveCrossOrigin && registrations.isEmpty()) {
            return DEFAULT_CORS_CONFIGURATION_PROVIDER;
        }

        CrossOrigin typeAnnotation = AnnotatedElementUtils.findMergedAnnotation(context.getUserClass(), CrossOrigin.class);
        CrossOrigin methodAnnotation = AnnotatedElementUtils.findMergedAnnotation(context.getMethod(), CrossOrigin.class);

        CorsConfiguration config = new CorsConfiguration();
        updateCorsConfig(config, typeAnnotation);
        updateCorsConfig(config, methodAnnotation);
        if (CollectionUtils.isEmpty(config.getAllowedMethods())) {
            for (Matcher matcher : context.getMatchers()) {
                if (matcher instanceof HttpMethodMatcher) {
                    HttpMethodMatcher methodMatcher = (HttpMethodMatcher) matcher;
                    for (HttpMethod method : methodMatcher.getHttpMethods()) {
                        config.addAllowedMethod(method.name());
                    }
                }
            }
        }
        config.applyPermitDefaultValues();
        if (registrations.isEmpty()) {
            return new SimpleCorsConfigurationProvider(config);
        }

        String pathRule = context.getPathRule();

        CorsRegistration alwaysRegistration = null;
        List<CorsRegistration> runtimeRegistrations = new ArrayList<>();
        for (CorsRegistration registration : registrations) {
            ContainmentResult containmentResult = registration.matchPathRuleToCached(pathRule);
            if (containmentResult == ContainmentResult.ALWAYS) {
                alwaysRegistration = registration;
                break;
            } else if (containmentResult == ContainmentResult.RUNTIME) {
                runtimeRegistrations.add(registration);
            }
        }
        if (alwaysRegistration != null) {
            return new SimpleCorsConfigurationProvider(alwaysRegistration.getCorsConfiguration().combine(config));
        }
        if (runtimeRegistrations.isEmpty()) {
            if (haveCrossOrigin) {
                return new SimpleCorsConfigurationProvider(config);
            } else {
                return DEFAULT_CORS_CONFIGURATION_PROVIDER;
            }
        }
        RuntimeMappingCorsConfigurationProvider provider = new RuntimeMappingCorsConfigurationProvider();
        for (CorsRegistration registration : runtimeRegistrations) {
            provider.addCorsConfiguration(registration.getPathPattern(), registration.getCorsConfiguration().combine(config));
        }
        return provider;
    }

    private void updateCorsConfig(CorsConfiguration config, @Nullable CrossOrigin annotation) {
        if (annotation == null) {
            return;
        }
        for (String origin : annotation.origins()) {
            config.addAllowedOrigin(resolveCorsAnnotationValue(origin));
        }
        for (RequestMethod method : annotation.methods()) {
            config.addAllowedMethod(method.name());
        }
        for (String header : annotation.allowedHeaders()) {
            config.addAllowedHeader(resolveCorsAnnotationValue(header));
        }
        for (String header : annotation.exposedHeaders()) {
            config.addExposedHeader(resolveCorsAnnotationValue(header));
        }

        String allowCredentials = resolveCorsAnnotationValue(annotation.allowCredentials());
        if ("true".equalsIgnoreCase(allowCredentials)) {
            config.setAllowCredentials(true);
        } else if ("false".equalsIgnoreCase(allowCredentials)) {
            config.setAllowCredentials(false);
        } else if (!allowCredentials.isEmpty()) {
            throw new IllegalStateException("@CrossOrigin's allowCredentials value must be \"true\", \"false\", " +
                    "or an empty string (\"\"): current value is [" + allowCredentials + "]");
        }

        if (annotation.maxAge() >= 0 && config.getMaxAge() == null) {
            config.setMaxAge(annotation.maxAge());
        }
    }

    private String resolveCorsAnnotationValue(String value) {
        if (this.embeddedValueResolver != null) {
            String resolved = this.embeddedValueResolver.resolveStringValue(value);
            return (resolved != null ? resolved : "");
        } else {
            return value;
        }
    }

    @Override
    public void setEmbeddedValueResolver(StringValueResolver resolver) {
        this.embeddedValueResolver = resolver;
    }
}

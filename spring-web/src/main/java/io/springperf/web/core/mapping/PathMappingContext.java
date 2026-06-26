package io.springperf.web.core.mapping;

import io.springperf.web.core.cors.provider.CorsConfigurationProvider;
import io.springperf.web.core.filter.WebFilter;
import io.springperf.web.core.interceptor.HandlerInterceptor;
import io.springperf.web.core.invoker.CustomInvoker;
import io.springperf.web.core.mapping.match.ConsumeOrProduceMatcher;
import io.springperf.web.core.mapping.match.Matcher;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.WebServerHttpRequest;
import org.springframework.http.MediaType;
import org.springframework.util.CollectionUtils;
import org.springframework.web.method.HandlerMethod;

import java.util.Arrays;
import java.util.List;

public class PathMappingContext extends MappingHandlerMethod {

    private static final RequestAttribute<PathMappingContext> REQUEST_ATTRIBUTE = RequestAttribute.createAttribute(PathMappingContext.class);

    private static final RequestAttribute<PathMappingContext[]> REQUEST_ATTRIBUTE_ARRAY = RequestAttribute.createAttribute(PathMappingContext[].class);

    private static final Matcher[] EMPTY_MATCHERS = new Matcher[0];

    private final String type;

    private final Matcher[] matchers;
    private final String pathRule;
    private final List<MediaType> producibleMediaTypes;
    private List<HandlerInterceptor> cachedInterceptors;
    private CorsConfigurationProvider corsConfigurationProvider;
    private List<WebFilter> cachedFilters;


    public PathMappingContext(HandlerMethod handlerMethod, List<Matcher> matchers, String pathRule) {
        super(handlerMethod);
        this.type = "Controller";
        this.matchers = CollectionUtils.isEmpty(matchers) ? EMPTY_MATCHERS : matchers.toArray(new Matcher[0]);
        this.producibleMediaTypes = initProducibleMediaTypes();
        this.pathRule = pathRule;
    }

    public PathMappingContext(CustomInvoker invoker, String pathRule) {
        super(invoker, invoker.getHandleMethod());
        this.type = invoker.getType();
        this.matchers = CollectionUtils.isEmpty(invoker.getMatchers()) ? EMPTY_MATCHERS : invoker.getMatchers().toArray(new Matcher[0]);
        this.producibleMediaTypes = initProducibleMediaTypes();
        this.pathRule = pathRule;
    }

    protected List<MediaType> initProducibleMediaTypes() {
        List<MediaType> result = null;
        for (Matcher matcher : matchers) {
            if (matcher instanceof ConsumeOrProduceMatcher) {
                ConsumeOrProduceMatcher consumeOrProduceMatcher = (ConsumeOrProduceMatcher) matcher;
                result = consumeOrProduceMatcher.getProducibleMediaTypes();
                if (result != null) {
                    break;
                }
            }
        }
        return result;
    }

    public Matcher[] getMatchers() {
        return matchers;
    }

    public String getPathRule() {
        return pathRule;
    }

    public List<HandlerInterceptor> getCachedInterceptors() {
        return cachedInterceptors;
    }

    public void setCachedInterceptors(List<HandlerInterceptor> cachedInterceptors) {
        this.cachedInterceptors = cachedInterceptors;
    }

    public List<WebFilter> getCachedFilters() {
        return cachedFilters;
    }

    public void setCachedFilters(List<WebFilter> cachedFilters) {
        this.cachedFilters = cachedFilters;
    }

    public List<MediaType> getProducibleMediaTypes() {
        return producibleMediaTypes;
    }

    @Override
    public String toString() {
        if (matchers.length == 0) {
            return type + ":" + pathRule;
        } else {
            return type + ":" + pathRule + " " + Arrays.toString(matchers);
        }
    }

    public CorsConfigurationProvider getCorsConfigurationProvider() {
        return corsConfigurationProvider;
    }

    public void setCorsConfigurationProvider(CorsConfigurationProvider corsConfigurationProvider) {
        this.corsConfigurationProvider = corsConfigurationProvider;
    }

    public static PathMappingContext get(WebServerHttpRequest request) {
        return request.getRequestContext().getAttribute(REQUEST_ATTRIBUTE);
    }

    public static void set(WebServerHttpRequest request, PathMappingContext mappingContext) {
        request.getRequestContext().setAttribute(REQUEST_ATTRIBUTE, mappingContext);
    }

    public static PathMappingContext[] getMatchPathMappingContexts(WebServerHttpRequest request) {
        return request.getRequestContext().getAttribute(REQUEST_ATTRIBUTE_ARRAY);
    }

    public static void setMatchPathMappingContexts(WebServerHttpRequest request, PathMappingContext[] mappingContext) {
        request.getRequestContext().setAttribute(REQUEST_ATTRIBUTE_ARRAY, mappingContext);
    }
}

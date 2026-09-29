package io.springperf.web.core.mapping;

import java.util.Arrays;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.util.CollectionUtils;
import org.springframework.web.method.HandlerMethod;

import io.springperf.web.core.cors.provider.CorsConfigurationProvider;
import io.springperf.web.core.filter.DefaultFilterChain;
import io.springperf.web.core.interceptor.HandlerInterceptor;
import io.springperf.web.core.invoker.CustomInvoker;
import io.springperf.web.core.mapping.match.ConsumeOrProduceMatcher;
import io.springperf.web.core.mapping.match.Matcher;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.WebServerHttpRequest;

public class PathMappingContext extends MappingHandlerMethod {

    private static final RequestAttribute<PathMappingContext[]> REQUEST_ATTRIBUTE_ARRAY = RequestAttribute
            .createAttribute(PathMappingContext[].class);

    private static final Matcher[] EMPTY_MATCHERS = new Matcher[0];

    private final String type;

    private final Matcher[] matchers;
    private final String pathRule;
    private final List<MediaType> producibleMediaTypes;
    /**
     * 只读视图缓存。该 getter 落在**每次响应体编码 / 内容协商**的路径上（HttpBodyCodecRegistry、
     * ReactiveReturnValueResolver），逐次新建包装等于每请求白扔一个对象；字段本身是 final， 故在构造期建好即可，无需任何同步。
     */
    private final List<MediaType> producibleMediaTypesView;
    /**
     * 延迟构建的拦截器/过滤器链缓存，由 InterceptorRegistry、WebFilterRegistry 在首次请求命中该路由时以双重检查锁定写入， 此后在每个请求的匹配路径上被无锁读取。字段必须
     * volatile：否则锁外的读者可能观察到非空引用但其内部 elementData/元素尚未完成发布，导致该请求静默少执行若干拦截器或过滤器。
     */
    private volatile List<HandlerInterceptor> cachedInterceptors;
    private CorsConfigurationProvider corsConfigurationProvider;
    private volatile DefaultFilterChain cachedFilterChain;

    public PathMappingContext(HandlerMethod handlerMethod, List<Matcher> matchers, String pathRule) {
        super(handlerMethod);
        this.type = "Controller";
        this.matchers = CollectionUtils.isEmpty(matchers) ? EMPTY_MATCHERS : matchers.toArray(new Matcher[0]);
        this.producibleMediaTypes = initProducibleMediaTypes();
        this.producibleMediaTypesView = producibleMediaTypes == null ? null
                : java.util.Collections.unmodifiableList(producibleMediaTypes);
        this.pathRule = pathRule;
    }

    public PathMappingContext(CustomInvoker invoker, String pathRule) {
        super(invoker, invoker.getHandleMethod());
        this.type = invoker.getType();
        this.matchers = CollectionUtils.isEmpty(invoker.getMatchers()) ? EMPTY_MATCHERS
                : invoker.getMatchers().toArray(new Matcher[0]);
        this.producibleMediaTypes = initProducibleMediaTypes();
        this.producibleMediaTypesView = producibleMediaTypes == null ? null
                : java.util.Collections.unmodifiableList(producibleMediaTypes);
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
        // 回退只读化：PathMappingContextTest#interceptors_getAndSet 以 assertSame 锁定「返回同一实例」
        // （含 Collections.emptyList() 这一特例），故保留直接引用 + 按字段豁免并写明依据。
        return cachedInterceptors;
    }

    public void setCachedInterceptors(List<HandlerInterceptor> cachedInterceptors) {
        this.cachedInterceptors = cachedInterceptors;
    }

    public DefaultFilterChain getCachedFilterChain() {
        return cachedFilterChain;
    }

    public void setCachedFilterChain(DefaultFilterChain cachedFilterChain) {
        this.cachedFilterChain = cachedFilterChain;
    }

    public List<MediaType> getProducibleMediaTypes() {
        // 只读视图（构造期建好，见 producibleMediaTypesView）；字段为 null 时仍返回 null
        return producibleMediaTypesView;
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
        MappingResult mr = MappingResult.get(request);
        if (mr != null && mr.isMatched()) {
            return mr.getMatchedContext();
        }
        return null;
    }

    public static PathMappingContext[] getMatchPathMappingContexts(WebServerHttpRequest request) {
        return request.getRequestContext().getAttribute(REQUEST_ATTRIBUTE_ARRAY);
    }

    public static void setMatchPathMappingContexts(WebServerHttpRequest request, PathMappingContext[] mappingContext) {
        request.getRequestContext().setAttribute(REQUEST_ATTRIBUTE_ARRAY, mappingContext);
    }
}

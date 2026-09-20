package io.springperf.web.core.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.resolver.AbstractNamedValueNullableResolver;
import io.springperf.web.core.arg.resolver.AbstractSupportOptionalResolver;
import io.springperf.web.core.arg.resolver.MultiValueMapResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.util.MultiValueMap;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.Map;

public abstract class AbstractSupportResolverProvider<T> implements StaticArgumentResolverProvider {

    protected abstract MultiValueMapResolver<T> getMultiValueMapResolver();

    @Override
    public StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        if (isMultiValueMap(parameter, mappingContext, webContext)) {
            return getMultiValueMapArgumentResolver(parameter, mappingContext, webContext);
        } else if (isSimpleMap(parameter, mappingContext, webContext)) {
            return getSimpleMapArgumentResolver(parameter, mappingContext, webContext);
        } else if (isCollection(parameter, mappingContext, webContext)) {
            return getCollectionArgumentResolver(parameter, mappingContext, webContext);
        } else {
            return getSingleValueArgumentResolver(parameter, mappingContext, webContext);
        }
    }

    protected abstract Class<? extends Annotation>[] supportAnnotationClass();

    protected boolean isMultiValueMap(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        Class<?> paramType = parameter.getNestedParameterType();
        return MultiValueMap.class.isAssignableFrom(paramType);
    }

    protected boolean isSimpleMap(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        Class<?> paramType = parameter.getNestedParameterType();
        return Map.class.isAssignableFrom(paramType);
    }

    protected boolean isCollection(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        Class<?> paramType = parameter.getNestedParameterType();
        return Collection.class.isAssignableFrom(paramType) || paramType.isArray();
    }

    protected StaticArgumentResolver getMultiValueMapArgumentResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        return new AbstractSupportOptionalResolver(mappingContext, parameter) {
            @Override
            protected Object doResolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
                return getMultiValueMapResolver().resolveMultiValueMap(parameter, mappingContext, request, response);
            }
        };
    }

    protected StaticArgumentResolver getSimpleMapArgumentResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        return new AbstractSupportOptionalResolver(mappingContext, parameter) {
            @Override
            protected Object doResolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
                MultiValueMap<String, T> multiValueMap = getMultiValueMapResolver().resolveMultiValueMap(parameter, mappingContext, request, response);
                return multiValueMap == null ? null : multiValueMap.toSingleValueMap();
            }
        };
    }

    protected StaticArgumentResolver getCollectionArgumentResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        return new AbstractNamedValueNullableResolver(webContext, mappingContext, parameter, supportAnnotationClass()) {
            @Override
            protected Object resolveByName(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
                MultiValueMap<String, T> multiValueMap = getMultiValueMapResolver().resolveMultiValueMap(parameter, mappingContext, request, response);
                return multiValueMap == null ? null : multiValueMap.get(name);
            }
        };
    }

    protected StaticArgumentResolver getSingleValueArgumentResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext) {
        return new AbstractNamedValueNullableResolver(webContext, mappingContext, parameter, supportAnnotationClass()) {
            @Override
            protected Object resolveByName(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
                if (useRequestSideParameterLookup()) {
                    // 请求侧直查：@RequestParam 走查询串直扫（见 NettyServerHttpRequest#getParameter），
                    // 免去每参数一次的 MultiValueMap 包装与哈希查找
                    Object direct = request.getParameter(name);
                    if (direct != null) {
                        return direct;
                    }
                    // 未命中（或请求实现未覆写 getParameter，如自定义实现/测试替身）：回退 MultiValueMap
                    // 通道 —— 结果与历史行为完全一致（真实实现下两者同义：getParameter = map.getFirst），
                    // 因此不会因请求实现差异而改变解析结果。
                }
                MultiValueMap<String, T> multiValueMap = getMultiValueMapResolver().resolveMultiValueMap(parameter, mappingContext, request, response);
                return multiValueMap == null ? null : multiValueMap.getFirst(name);
            }
        };
    }

    /**
     * 单值命名参数是否改走请求侧直查 {@link WebServerHttpRequest#getParameter(String)}。
     *
     * <p>仅当两者语义等价时才应由子类覆写为 {@code true}：对 {@code @RequestParam}，请求侧
     * {@code getParameter} 与 {@code getParameterMap().getFirst(name)} 同义（基础实现即为后者），
     * 但前者在纯查询请求上可走零哈希直扫快路径。{@code @RequestHeader} 等仍返回 {@code false}。</p>
     */
    protected boolean useRequestSideParameterLookup() {
        return false;
    }
}

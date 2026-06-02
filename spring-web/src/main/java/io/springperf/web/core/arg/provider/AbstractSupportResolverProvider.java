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
                MultiValueMap<String, T> multiValueMap = getMultiValueMapResolver().resolveMultiValueMap(parameter, mappingContext, request, response);
                return multiValueMap == null ? null : multiValueMap.getFirst(name);
            }
        };
    }
}

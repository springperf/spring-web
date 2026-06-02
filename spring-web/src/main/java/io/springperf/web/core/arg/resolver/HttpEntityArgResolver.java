package io.springperf.web.core.arg.resolver;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.codec.HttpBodyCodecRegistry;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpEntity;
import org.springframework.http.RequestEntity;
import org.springframework.util.Assert;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

public class HttpEntityArgResolver implements StaticArgumentResolver {


    private final WebContext webContext;

    private final MethodParameter parameter;

    private final boolean isRequestEntity;

    private final Type bodyParameterType;

    private final HttpBodyCodecRegistry httpBodyCodecRegistry;

    public HttpEntityArgResolver(WebContext webContext, MethodParameter parameter) {
        this.webContext = webContext;
        this.parameter = parameter;
        this.isRequestEntity = RequestEntity.class == parameter.getParameterType();
        this.bodyParameterType = getHttpEntityType(parameter);
        this.httpBodyCodecRegistry = webContext.getWebComponent(HttpBodyCodecRegistry.class);
    }

    @Override
    public Object resolveArgument(WebServerHttpRequest request, WebServerHttpResponse response) throws Exception {
        Object body = httpBodyCodecRegistry.readBody(bodyParameterType, parameter, request, request);
        if (isRequestEntity) {
            return new RequestEntity<>(body, request.getHeaders(), request.getMethod(), request.getURI());
        } else {
            return new HttpEntity<>(body, request.getHeaders());
        }
    }

    private Type getHttpEntityType(MethodParameter parameter) {
        Assert.isAssignable(HttpEntity.class, parameter.getParameterType());
        Type parameterType = parameter.getGenericParameterType();
        if (parameterType instanceof ParameterizedType) {
            ParameterizedType type = (ParameterizedType) parameterType;
            if (type.getActualTypeArguments().length != 1) {
                throw new IllegalArgumentException("Expected single generic parameter on '" +
                        parameter.getParameterName() + "' in method " + parameter.getMethod());
            }
            return type.getActualTypeArguments()[0];
        } else if (parameterType instanceof Class) {
            return Object.class;
        } else {
            return null;
        }
    }
}

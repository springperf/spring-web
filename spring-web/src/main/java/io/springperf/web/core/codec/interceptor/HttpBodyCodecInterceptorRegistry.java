package io.springperf.web.core.codec.interceptor;

import io.springperf.web.context.WebComponentContainer;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.codec.HttpBodyConverter;
import io.springperf.web.core.mapping.MappingCacheKey;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.web.method.ControllerAdviceBean;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class HttpBodyCodecInterceptorRegistry extends WebComponentContainer {
    protected static final MappingCacheKey<HttpBodyCodecInterceptor[]> MAPPING_CACHE_KEY = MappingCacheKey.createClassCacheKey(HttpBodyCodecInterceptor[].class);
    protected final List<WebComponentControllerAdviceBean<HttpBodyCodecInterceptor>> codecInterceptors = new ArrayList<>();

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        initCodecInterceptors();
    }

    protected void initCodecInterceptors() {
        List<ControllerAdviceBean> adviceBeans = getControllerAdviceBean(HttpBodyCodecInterceptor.class);
        for (ControllerAdviceBean adviceBean : adviceBeans) {
            registerWebComponent(new WebComponentControllerAdviceBean<>(adviceBean));
        }
        Map<String, HttpBodyCodecInterceptor> beanMap = webContext.getCtx().getBeansOfType(HttpBodyCodecInterceptor.class);
        for (Map.Entry<String, HttpBodyCodecInterceptor> entry : beanMap.entrySet()) {
            registerWebComponent(new WebComponentControllerAdviceBean<>(entry.getKey(), entry.getValue()));
        }
    }

    protected List<ControllerAdviceBean> getControllerAdviceBean(Class<?> beanType) {
        List<ControllerAdviceBean> adviceBeans = ControllerAdviceBean.findAnnotatedBeans(webContext.getCtx());
        return adviceBeans.stream().filter(adviceBean -> beanType == null || beanType.isAssignableFrom(adviceBean.getBeanType())).collect(Collectors.toList());
    }

    @Override
    public void initComponentPhase1() throws Exception {
        super.initComponentPhase1();
        initRealComponentList((List) codecInterceptors, WebComponentControllerAdviceBean.class);
    }

    protected HttpBodyCodecInterceptor[] getCodecInterceptor(WebServerHttpRequest request, MethodParameter parameter) {
        PathMappingContext mappingContext = PathMappingContext.get(request);
        if (mappingContext == null) {
            return realGetCodecInterceptor(parameter);
        }
        HttpBodyCodecInterceptor[] interceptors = mappingContext.get(MAPPING_CACHE_KEY);
        if (interceptors == null) {
            interceptors = realGetCodecInterceptor(parameter);
            mappingContext.set(MAPPING_CACHE_KEY, interceptors);
        }
        return interceptors;
    }

    protected HttpBodyCodecInterceptor[] realGetCodecInterceptor(MethodParameter parameter) {
        List<HttpBodyCodecInterceptor> interceptors = new ArrayList<>();
        for (WebComponentControllerAdviceBean<HttpBodyCodecInterceptor> interceptor : codecInterceptors) {
            if (interceptor.isApplicableToBeanType(parameter.getContainingClass())) {
                interceptors.add(interceptor.getComponent());
            }
        }
        return interceptors.toArray(new HttpBodyCodecInterceptor[0]);
    }


    public HttpInputMessage beforeBodyRead(WebServerHttpRequest request, HttpInputMessage inputMessage, MethodParameter parameter,
                                           Type targetType, HttpBodyConverter converter) throws IOException {
        HttpBodyCodecInterceptor[] interceptors = getCodecInterceptor(request, parameter);
        for (HttpBodyCodecInterceptor interceptor : interceptors) {
            if (interceptor.supportBodyRead(parameter, targetType, converter)) {
                inputMessage = interceptor.beforeBodyRead(inputMessage, parameter, targetType, converter);
            }
        }
        return inputMessage;
    }

    public Object afterBodyRead(WebServerHttpRequest request, Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                Type targetType, HttpBodyConverter converter) {
        HttpBodyCodecInterceptor[] interceptors = getCodecInterceptor(request, parameter);
        for (HttpBodyCodecInterceptor interceptor : interceptors) {
            if (interceptor.supportBodyRead(parameter, targetType, converter)) {
                body = interceptor.afterBodyRead(body, inputMessage, parameter, targetType, converter);
            }
        }
        return body;
    }

    public Object handleEmptyBodyRead(WebServerHttpRequest request, @Nullable Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                      Type targetType, HttpBodyConverter converter) {
        HttpBodyCodecInterceptor[] interceptors = getCodecInterceptor(request, parameter);
        for (HttpBodyCodecInterceptor interceptor : interceptors) {
            if (interceptor.supportBodyRead(parameter, targetType, converter)) {
                body = interceptor.handleEmptyBodyRead(body, inputMessage, parameter, targetType, converter);
            }
        }
        return body;
    }


    public Object beforeBodyWrite(@Nullable Object body, MethodParameter returnType, MediaType contentType,
                                  HttpBodyConverter converter,
                                  WebServerHttpRequest request, WebServerHttpResponse response) {
        HttpBodyCodecInterceptor[] interceptors = getCodecInterceptor(request, returnType);
        for (HttpBodyCodecInterceptor interceptor : interceptors) {
            if (interceptor.supportBodyWrite(returnType, converter)) {
                body = interceptor.beforeBodyWrite(body, returnType, contentType, converter, request, response);
            }
        }
        return body;
    }
}

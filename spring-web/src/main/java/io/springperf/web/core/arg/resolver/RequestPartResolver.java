package io.springperf.web.core.arg.resolver;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.codec.HttpBodyCodecRegistry;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.http.support.HttpInputMessagePart;
import org.springframework.core.MethodParameter;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartException;

import java.lang.reflect.Type;

public class RequestPartResolver extends AbstractNamedValueNullableResolver {

    private final HttpBodyCodecRegistry httpBodyCodecRegistry;

    private final Type targetType;

    public RequestPartResolver(WebContext webContext, MappingHandlerMethod mappingContext, MethodParameter methodParameter) {
        super(webContext, mappingContext, methodParameter, RequestPart.class);
        this.httpBodyCodecRegistry = webContext.getWebComponent(HttpBodyCodecRegistry.class);
        this.targetType = parameter.getGenericParameterType();
    }

    @Override
    protected Object resolveByName(WebServerHttpRequest request, WebServerHttpResponse response) {
        MultiValueMap<String, HttpInputMessagePart> partMap = request.getPartMap();
        if (partMap == null) {
            throw new MultipartException("Current request is not a multipart request");
        }
        HttpInputMessagePart part = partMap.getFirst(name);
        return httpBodyCodecRegistry.readBody(targetType, parameter, part, request);
    }
}

package io.springperf.web.core.retval.resolver;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.codec.HttpBodyCodecRegistry;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.retval.ReturnValueResolver;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

public class HttpEntityReturnValueResolver extends BaseWebComponent implements ReturnValueResolver {

    private HttpBodyCodecRegistry httpBodyCodecRegistry;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        httpBodyCodecRegistry = webContext.getWebComponent(HttpBodyCodecRegistry.class);
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        return HttpEntity.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        return returnValue instanceof HttpEntity;
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        HttpEntity httpEntity = (HttpEntity) returnValue;
        if (httpEntity instanceof ResponseEntity) {
            ResponseEntity<?> responseEntity = (ResponseEntity<?>) httpEntity;
            if (responseEntity.getStatusCode() != null) {
                resp.setStatusCode(responseEntity.getStatusCode());
            }
        }
        // 空 headers 时跳过 putAll，避免空 map 的无谓遍历
        HttpHeaders entityHeaders = httpEntity.getHeaders();
        if (entityHeaders != null && !entityHeaders.isEmpty()) {
            resp.getHeaders().putAll(entityHeaders);
        }
        Object body = httpEntity.getBody();
        if (body != null) {
            httpBodyCodecRegistry.writeBody(body, returnType, req, resp);
        }
    }
}

package io.springperf.web.core.retval.resolver;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.retval.ReturnValueResolver;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.Resource;

public class ResourceReturnValueResolver implements ReturnValueResolver {
    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        return Resource.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        return returnValue instanceof Resource;
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        Resource resource = (Resource) returnValue;
        if (resource.isFile()) {
            resp.writeFile(resource.getFile());
        } else {
            resp.writeStream(resource.getInputStream());
        }
    }
}

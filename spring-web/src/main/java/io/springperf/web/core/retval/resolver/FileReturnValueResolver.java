package io.springperf.web.core.retval.resolver;

import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.retval.ReturnValueResolver;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;

import java.io.File;
import java.nio.file.Path;

public class FileReturnValueResolver implements ReturnValueResolver {
    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        return File.class.isAssignableFrom(returnType.getParameterType()) || Path.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        return returnValue instanceof File || returnValue instanceof Path;
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        if (returnValue instanceof Path) {
            returnValue = ((Path) returnValue).toFile();
        }
        resp.writeFile((java.io.File) returnValue);
    }
}

package io.springperf.web.core.async.stream;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.retval.resolver.async.BaseAsyncReturnValueResolver;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.ResponseEntity;

public class StreamEmitterReturnValueResolver extends BaseAsyncReturnValueResolver {

    private StreamSenderFactory streamSenderFactory;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        streamSenderFactory = webContext.getWebComponentWithDefault(StreamSenderFactory.class, new DefaultStreamSenderFactory());
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        Class<?> bodyType = ResponseEntity.class.isAssignableFrom(returnType.getParameterType()) ?
                ResolvableType.forMethodParameter(returnType).getGeneric().resolve() :
                returnType.getParameterType();

        return bodyType != null && StreamEmitter.class.isAssignableFrom(bodyType);
    }

    @Override
    public boolean supportsReturnValue(Object returnValue, WebServerHttpRequest req, WebServerHttpResponse resp) {
        if (returnValue instanceof ResponseEntity) {
            returnValue = ((ResponseEntity<?>) returnValue).getBody();
        }
        return returnValue instanceof StreamEmitter;
    }

    @Override
    public void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        if (returnValue instanceof ResponseEntity) {
            ResponseEntity<?> responseEntity = (ResponseEntity<?>) returnValue;
            resp.setStatusCode(responseEntity.getStatusCode());
            resp.getHeaders().putAll(responseEntity.getHeaders());
            returnValue = responseEntity.getBody();
        }
        StreamEmitter emitter = (StreamEmitter) returnValue;
        preInitializeEmitter(emitter, req, resp);
        StreamSender sender = StreamEmitterUtil.initStreamSenderAndStartAsync(emitter, streamSenderFactory, asyncSupportRegistry, req, resp);
        StreamEmitterUtil.initializeWithStreamSender(emitter, sender);
    }

    protected void preInitializeEmitter(StreamEmitter emitter, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        StreamEmitterUtil.extendResponseAndFlush(emitter, resp, true);
    }
}

package io.springperf.web.core.async.stream;

import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.ResponseEntity;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.retval.resolver.async.BaseAsyncReturnValueResolver;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

public class StreamEmitterReturnValueResolver extends BaseAsyncReturnValueResolver {

    private StreamSenderFactory streamSenderFactory;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        streamSenderFactory = webContext.getWebComponentWithDefault(StreamSenderFactory.class,
                new DefaultStreamSenderFactory());
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType, MappingHandlerMethod mappingContext) {
        Class<?> bodyType = ResponseEntity.class.isAssignableFrom(returnType.getParameterType())
                ? ResolvableType.forMethodParameter(returnType).getGeneric().resolve()
                : returnType.getParameterType();

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
    public void resolveReturnValue(Object returnValue, MethodParameter returnType, WebServerHttpRequest req,
            WebServerHttpResponse resp) throws Exception {
        if (returnValue instanceof ResponseEntity) {
            ResponseEntity<?> responseEntity = (ResponseEntity<?>) returnValue;
            resp.setStatusCode(responseEntity.getStatusCode());
            // 不能写 putAll(headers)：Spring 7 的 putAll(HttpHeaders) 重载语义/行为均不同（见 WebHttpHeaders#addAllHeaders）
            io.springperf.web.http.WebHttpHeaders.addAllHeaders(resp.getHeaders(), responseEntity.getHeaders());
            returnValue = responseEntity.getBody();
        }
        // ResponseEntity.getBody() 合法可为 null：无 body 就没有可发送的流，直接返回
        if (returnValue == null) {
            return;
        }
        StreamEmitter emitter = (StreamEmitter) returnValue;
        preInitializeEmitter(emitter, req, resp);
        // HEAD：preInitializeEmitter 里的 flush(true) 已把响应以「仅响应头」的完整消息收尾
        // （RFC 7231 §4.3.2），语义上不存在 body。此处若照常建 StreamSender 并订阅发布者，
        // drain 的第一帧内容会撞上已被 Netty 复位为 INIT 的 HTTP 编码器：
        // EncoderException("unexpected message type: DefaultHttpContent, state: 0")
        // → writeStreamErrorCallback，把一次正常的 HEAD 收尾打成 ERROR 日志，还顺带把上游订阅 cancel 掉。
        // 响应已经写完整，不必也不得再启动流：没有 body 可写，发送器只会空转。
        if (resp.isHeadRequest()) {
            return;
        }
        StreamSender sender = StreamEmitterUtil.initStreamSenderAndStartAsync(emitter, streamSenderFactory,
                asyncSupportRegistry, req, resp);
        StreamEmitterUtil.initializeWithStreamSender(emitter, sender);
    }

    protected void preInitializeEmitter(StreamEmitter emitter, WebServerHttpRequest req, WebServerHttpResponse resp)
            throws Exception {
        StreamEmitterUtil.extendResponseAndFlush(emitter, resp, true);
    }
}

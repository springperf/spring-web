package io.springperf.web.core.async.stream;

import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.core.async.AsyncSupportUtils;
import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

import java.io.IOException;

public class StreamEmitterUtil {

    public static void extendResponseAndFlush(StreamEmitter emitter, WebServerHttpResponse response, boolean flush) throws IOException {
        emitter.extendResponse(response);
        if (flush) {
            response.flush(true);
        }
    }

    public static StreamSender initStreamSenderAndStartAsync(StreamEmitter emitter, StreamSenderFactory streamSenderFactory, AsyncSupportRegistry asyncSupportRegistry, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        StreamSender sender;
        try {
            PerfAsyncWebRequest asyncWebRequest = AsyncSupportUtils.getAsyncWebRequest(req, resp);
            asyncSupportRegistry.startDeferredResultProcessing(asyncWebRequest, emitter.getDeferredResult());
            sender = streamSenderFactory.create(emitter, asyncWebRequest);
        } catch (Throwable ex) {
            emitter.initializeWithError(ex);
            throw ex;
        }
        return sender;
    }

    /**
     * 将 emitter 的写回调同步给 asyncWebRequest，使 Netty 写完成后触发背压补充请求。
     * <p>
     * 生产流程已由 {@code PublisherToStreamEmitterAdapter#onSubscribe} 内部直接注册（订阅建立
     * 后立即同步，兼容 onSubscribe 异步投递的 Publisher）。本方法保留供测试或外部代码在
     * 订阅建立后手动同步绑定；调用前必须确保 {@code emitter.getWriteCallbackHandler()} 非 null，
     * 否则绑定 null 会导致写完成永不触发补充请求。
     */
    public static void bindWriteCallbackHandler(StreamEmitter emitter, WebServerHttpRequest req, WebServerHttpResponse resp) {
        PerfAsyncWebRequest asyncWebRequest = AsyncSupportUtils.getAsyncWebRequest(req, resp);
        asyncWebRequest.addWriteCallbackHandler(emitter.getWriteCallbackHandler());
    }

    public static void initializeWithStreamSender(StreamEmitter emitter, StreamSender streamSender) throws IOException {
        emitter.initialize(streamSender);
    }
}

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
     * 必须在 {@link #initStreamSenderAndStartAsync} 创建 sender 之后、且订阅建立
     * （{@code subscribe} → {@code onSubscribe} 注册 {@code emitter.onWriteCallback}）之后调用；
     * 否则 {@code emitter.getWriteCallbackHandler()} 仍为 null，写完成时补充请求永不触发，
     * 遵守背压的冷 Publisher 在 highWaterMark 条后流永久停滞。
     */
    public static void bindWriteCallbackHandler(StreamEmitter emitter, WebServerHttpRequest req, WebServerHttpResponse resp) {
        PerfAsyncWebRequest asyncWebRequest = AsyncSupportUtils.getAsyncWebRequest(req, resp);
        asyncWebRequest.addWriteCallbackHandler(emitter.getWriteCallbackHandler());
    }

    public static void initializeWithStreamSender(StreamEmitter emitter, StreamSender streamSender) throws IOException {
        emitter.initialize(streamSender);
    }
}

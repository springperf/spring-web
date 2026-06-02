package io.springperf.web.core.async.stream;

import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.core.async.AsyncSupportUtils;
import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.http.server.ServerHttpResponse;

import java.io.IOException;

public class StreamEmitterUtil {

    public static void extendResponseAndFlush(StreamEmitter emitter, ServerHttpResponse response, boolean flush) throws IOException {
        emitter.extendResponse(response);
        if (flush) {
            response.flush();
        }
    }

    public static StreamSender initStreamSenderAndStartAsync(StreamEmitter emitter, StreamSenderFactory streamSenderFactory, AsyncSupportRegistry asyncSupportRegistry, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        StreamSender sender;
        try {
            PerfAsyncWebRequest asyncWebRequest = AsyncSupportUtils.getAsyncWebRequest(req, resp);
            asyncWebRequest.addWriteCallbackHandler(emitter.getWriteCallbackHandler());
            asyncSupportRegistry.startDeferredResultProcessing(asyncWebRequest, emitter.getDeferredResult());
            sender = streamSenderFactory.create(emitter, asyncWebRequest);
        } catch (Throwable ex) {
            emitter.initializeWithError(ex);
            throw ex;
        }
        return sender;
    }

    public static void initializeWithStreamSender(StreamEmitter emitter, StreamSender streamSender) throws IOException {
        emitter.initialize(streamSender);
    }
}

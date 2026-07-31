package io.springperf.web.core.async.stream;

import io.springperf.web.core.async.PerfAsyncWebRequest;

public class DefaultStreamSenderFactory implements StreamSenderFactory {
    @Override
    public StreamSender create(StreamEmitter streamEmitter, PerfAsyncWebRequest asyncWebRequest) {
        if (streamEmitter.encodeToString) {
            return new StringNettyStreamSender(streamEmitter, asyncWebRequest);
        } else {
            return new BytesNettyStreamSender(streamEmitter, asyncWebRequest);
        }
    }
}

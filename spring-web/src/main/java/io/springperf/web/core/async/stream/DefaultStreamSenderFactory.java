package io.springperf.web.core.async.stream;

import io.springperf.web.core.async.PerfAsyncWebRequest;

public class DefaultStreamSenderFactory implements StreamSenderFactory {
    @Override
    public StreamSender create(StreamEmitter streamEmitter, PerfAsyncWebRequest asyncWebRequest) {
        if (streamEmitter.isEarlyEncode()) {
            return new EarlyEncodeNettyStreamSender(streamEmitter, asyncWebRequest);
        }
        return new DefaultNettyStreamSender(streamEmitter, asyncWebRequest);
    }
}

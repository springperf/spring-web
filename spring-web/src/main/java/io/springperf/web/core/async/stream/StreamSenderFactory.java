package io.springperf.web.core.async.stream;

import io.springperf.web.context.WebComponent;
import io.springperf.web.core.async.PerfAsyncWebRequest;

public interface StreamSenderFactory extends WebComponent {

    StreamSender create(StreamEmitter streamEmitter, PerfAsyncWebRequest asyncWebRequest);
}

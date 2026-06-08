package io.springperf.web.core.async.stream;

import io.springperf.web.context.WebComponent;
import io.springperf.web.core.async.PerfAsyncWebRequest;

/**
 * Factory SPI for creating {@link StreamSender} instances per-stream.
 *
 * <p>Each asynchronous streaming response gets its own {@code StreamSender}
 * instance, created by this factory. Implementations may return different
 * sender types depending on the stream characteristics.</p>
 *
 * @since 1.0.0
 * @see StreamSender
 * @see StreamEmitter
 */
public interface StreamSenderFactory extends WebComponent {

    /**
     * Create a {@link StreamSender} for the given emitter and async request.
     *
     * @param streamEmitter    the stream emitter producing data
     * @param asyncWebRequest  the async web request context
     * @return a new stream sender instance
     */
    StreamSender create(StreamEmitter streamEmitter, PerfAsyncWebRequest asyncWebRequest);
}

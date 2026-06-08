package io.springperf.web.core.async.stream;

import java.io.IOException;

/**
 * Contract for sending data chunks over an asynchronous streaming response.
 *
 * <p>Used in conjunction with {@link StreamEmitter} and
 * {@link StreamSenderFactory} to support reactive-style streaming responses
 * such as Server-Sent Events (SSE) and chunked JSON arrays.</p>
 *
 * @since 1.0.0
 * @see StreamSenderFactory
 * @see StreamEmitter
 */
public interface StreamSender {

    /**
     * Send a data chunk to the client.
     *
     * @param data the data to send
     * @throws IOException if the underlying channel write fails
     */
    void send(Object data) throws IOException;

    /**
     * Complete the stream, optionally closing the channel.
     *
     * @param closeChannelOnComplete whether to close the TCP connection
     * @param failure                the cause if the stream failed, or {@code null} for normal completion
     */
    void complete(boolean closeChannelOnComplete, Throwable failure);

    /**
     * Return the number of queued items waiting to be sent.
     *
     * @return the current queue depth
     */
    int queueSize();
}

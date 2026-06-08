package io.springperf.web.http;

import io.springperf.web.util.DefaultLoggerUtil;

/**
 * Callback interface for monitoring the lifecycle of asynchronous response writing.
 *
 * <p>Implementations receive notifications when the response has been fully
 * written to the client ({@link #completeSuccessCallback}), when an error
 * occurs during writing ({@link #completeErrorCallback}), and optionally
 * per-chunk for streaming responses ({@link #writeStreamSuccessCallback},
 * {@link #writeStreamErrorCallback}).</p>
 *
 * @since 1.0.0
 */
public interface WriteRespEventListener {

    /**
     * Called when the complete response has been successfully written.
     */
    void completeSuccessCallback();

    /**
     * Called when an error occurs during response writing.
     *
     * @param throwable the failure cause
     */
    void completeErrorCallback(Throwable throwable);

    /**
     * Called after each successful chunk write in a streaming response.
     */
    default void writeStreamSuccessCallback() {

    }

    /**
     * Called when a chunk write fails in a streaming response.
     *
     * @param throwable the failure cause
     */
    default void writeStreamErrorCallback(Throwable throwable) {
        DefaultLoggerUtil.log.error("write error", throwable);
    }
}

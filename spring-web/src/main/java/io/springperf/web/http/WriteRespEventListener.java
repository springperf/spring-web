package io.springperf.web.http;

import io.springperf.web.util.DefaultLoggerUtil;

public interface WriteRespEventListener {

    void completeSuccessCallback();

    void completeErrorCallback(Throwable throwable);

    default void writeStreamSuccessCallback() {

    }

    default void writeStreamErrorCallback(Throwable throwable) {
        DefaultLoggerUtil.log.error("write error", throwable);
    }
}

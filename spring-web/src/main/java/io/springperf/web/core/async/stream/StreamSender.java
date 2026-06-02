package io.springperf.web.core.async.stream;

import java.io.IOException;

public interface StreamSender {

    void send(Object data) throws IOException;

    void complete(boolean closeChannelOnComplete, Throwable failure);

    int queueSize();
}

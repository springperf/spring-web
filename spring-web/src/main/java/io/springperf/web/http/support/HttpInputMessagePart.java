package io.springperf.web.http.support;

public interface HttpInputMessagePart extends BodyHttpInputMessage {

    String getName();

    long getSize();

    String getSubmittedFileName();
}

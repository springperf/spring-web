package io.springperf.web.http.support;

import org.springframework.http.HttpInputMessage;

public interface BodyHttpInputMessage extends HttpInputMessage {

    boolean hasBody();
}

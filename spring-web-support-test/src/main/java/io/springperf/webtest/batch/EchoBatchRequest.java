package io.springperf.webtest.batch;

import io.springperf.web.batch.common.BatchRequest;

public class EchoBatchRequest extends BatchRequest<String> {
    public String msg;
    public UserBody body;
    public String pathVal;

    public EchoBatchRequest() {
    }

    public EchoBatchRequest(String msg) {
        this.msg = msg;
    }

    public EchoBatchRequest(String msg, UserBody body, String pathVal) {
        this.msg = msg;
        this.body = body;
        this.pathVal = pathVal;
    }
}

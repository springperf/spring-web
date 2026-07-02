package io.springperf.example.batch.request;

import io.springperf.web.batch.common.BatchRequest;

public class GetUserRequest extends BatchRequest<String> {

    private final String id;
    private final String fields;

    public GetUserRequest(String id, String fields) {
        this.id = id;
        this.fields = fields;
    }

    public String getId() {
        return id;
    }

    public String getFields() {
        return fields;
    }
}

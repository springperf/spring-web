package io.springperf.example.batch.request;

import io.springperf.example.batch.model.UserBody;
import io.springperf.web.batch.common.BatchRequest;

public class CreateUserRequest extends BatchRequest<String> {

    private final String name;
    private final UserBody body;

    public CreateUserRequest(String name, UserBody body) {
        this.name = name;
        this.body = body;
    }

    public String getName() {
        return name;
    }

    public UserBody getBody() {
        return body;
    }
}

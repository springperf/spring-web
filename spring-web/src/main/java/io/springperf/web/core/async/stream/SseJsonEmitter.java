package io.springperf.web.core.async.stream;

import java.io.IOException;
import java.io.OutputStream;

import io.springperf.web.json.JsonConverter;

public class SseJsonEmitter extends SseEmitter {

    private final JsonConverter jsonConverter;

    public SseJsonEmitter(JsonConverter jsonConverter) {
        super();
        this.jsonConverter = jsonConverter;
    }

    public SseJsonEmitter(Long timeout, JsonConverter jsonConverter) {
        super(timeout);
        this.jsonConverter = jsonConverter;
    }

    @Override
    protected void encodeEventDataAsBytes(Object data, OutputStream out) throws IOException {
        jsonConverter.toJson(out, data);
    }
}

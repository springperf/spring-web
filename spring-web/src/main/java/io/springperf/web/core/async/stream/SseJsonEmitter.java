package io.springperf.web.core.async.stream;

import io.springperf.web.json.JsonConverter;

import java.io.IOException;

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
    protected String encodeEventData(Object data) {
        return jsonConverter.toJson(data);
    }

    @Override
    protected byte[] encodeEventDataAsBytes(Object data) throws IOException {
        return jsonConverter.toJsonBytes(data);
    }
}

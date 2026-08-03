package io.springperf.web.core.async.stream;

import io.springperf.web.json.JsonConverter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;

import java.io.IOException;
import java.io.OutputStream;

public class StreamJsonEmitter extends StreamEmitter<Object> {

    private final JsonConverter jsonConverter;

    public StreamJsonEmitter(JsonConverter jsonConverter) {
        super();
        this.jsonConverter = jsonConverter;
    }

    public StreamJsonEmitter(Long timeout, JsonConverter jsonConverter) {
        super(timeout);
        this.jsonConverter = jsonConverter;
    }

    @Override
    protected void extendResponse(ServerHttpResponse response) {
        HttpHeaders headers = response.getHeaders();
        if (headers.getContentType() == null) {
            headers.setContentType(MediaType.APPLICATION_STREAM_JSON);
        }
    }

    @Override
    public void encode(Object data, OutputStream out) throws IOException {
        if (data == null) {
            out.write('\n');
            return;
        }
        jsonConverter.toJson(out, data);
        out.write('\n');
    }
}
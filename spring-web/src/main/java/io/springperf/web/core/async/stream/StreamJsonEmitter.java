package io.springperf.web.core.async.stream;

import io.springperf.web.json.JsonConverter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;

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
    protected CharSequence encodeToString(Object data) {
        if (data == null) {
            return "\n";
        }
        String str = jsonConverter.toJson(data);
        StringBuilder sb = new StringBuilder(str);
        sb.append('\n');
        return sb;
    }
}

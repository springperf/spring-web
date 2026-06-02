package io.springperf.web.core.async.stream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;

public class SseEmitter extends StreamEmitter<Object> {
    public SseEmitter() {
    }

    public SseEmitter(Long timeout) {
        super(timeout, true);
    }

    @Override
    public void send(Object data) throws IOException {
        super.send(data);
    }

    @Override
    protected void extendResponse(ServerHttpResponse response) {
        HttpHeaders headers = response.getHeaders();
        if (headers.getContentType() == null) {
            headers.setContentType(MediaType.TEXT_EVENT_STREAM);
        }
        headers.setCacheControl("no-cache");
    }

    @Override
    protected CharSequence encodeToString(Object data) {
        if (data instanceof ServerSentEvent) {
            ServerSentEvent sse = (ServerSentEvent) data;
            return encodeToString(sse.id(), sse.event(), sse.retry(), sse.comment(), sse.data());
        } else {
            return encodeToString(null, null, null, null, data);
        }
    }

    protected CharSequence encodeToString(String id, String event, Duration retry, String comment, Object data) {
        StringBuilder sb = new StringBuilder();
        if (id != null) {
            writeField("id", id, sb);
        }
        if (event != null) {
            writeField("event", event, sb);
        }
        if (retry != null) {
            writeField("retry", retry.toMillis(), sb);
        }
        if (comment != null) {
            sb.append(':').append(StringUtils.replace(comment, "\n", "\n:")).append("\n");
        }
        if (data == null) {
            sb.append("\n");
            return sb;
        }
        sb.append("data:");
        String dataStr;
        if (data instanceof CharSequence) {
            dataStr = data.toString();
        } else {
            dataStr = encodeEventData(data);
        }
        dataStr = StringUtils.replace(dataStr, "\n", "\ndata:");
        sb.append(dataStr).append("\n\n");
        return sb;
    }

    protected String encodeEventData(Object data) {
        throw new UnsupportedOperationException();
    }


    protected void writeField(String fieldName, Object fieldValue, StringBuilder sb) {
        sb.append(fieldName).append(':').append(fieldValue).append("\n");
    }

    @Override
    protected int getMaxFlushBytes() {
        return 1024 * 4;
    }

}

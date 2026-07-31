package io.springperf.web.core.async.stream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class SseEmitter extends StreamEmitter<Object> {

    private static final byte[] DATA_PREFIX = "data:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEWLINE_DATA = "\ndata:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TERMINATOR = "\n\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEWLINE = "\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FIELD_ID = "id:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FIELD_EVENT = "event:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FIELD_RETRY = "retry:".getBytes(StandardCharsets.UTF_8);

    public SseEmitter() {
        super(true);
    }

    protected SseEmitter(boolean encodeToString) {
        super(encodeToString);
    }

    public SseEmitter(Long timeout) {
        super(timeout, true);
    }

    protected SseEmitter(Long timeout, boolean encodeToString) {
        super(timeout, encodeToString);
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
        StringBuilder sb;
        if (data instanceof CharSequence) {
            sb = new StringBuilder(((CharSequence) data).length() + 64);
        } else {
            sb = new StringBuilder(256);
        }
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

    @Override
    protected byte[] encodeToBytes(Object data) throws IOException {
        if (data instanceof ServerSentEvent) {
            ServerSentEvent sse = (ServerSentEvent) data;
            return encodeToBytes(sse.id(), sse.event(), sse.retry(), sse.comment(), sse.data());
        } else {
            return encodeToBytes(null, null, null, null, data);
        }
    }

    protected byte[] encodeToBytes(String id, String event, Duration retry, String comment, Object data) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream(256);

        if (id != null) {
            writeFieldBytes(os, FIELD_ID, id.getBytes(StandardCharsets.UTF_8));
        }
        if (event != null) {
            writeFieldBytes(os, FIELD_EVENT, event.getBytes(StandardCharsets.UTF_8));
        }
        if (retry != null) {
            writeFieldBytes(os, FIELD_RETRY, Long.toString(retry.toMillis()).getBytes(StandardCharsets.UTF_8));
        }
        if (comment != null) {
            os.write(':');
            String commentReplaced = StringUtils.replace(comment, "\n", "\n:");
            os.write(commentReplaced.getBytes(StandardCharsets.UTF_8));
            os.write(NEWLINE);
        }
        if (data == null) {
            os.write(NEWLINE);
            return os.toByteArray();
        }

        // 获取 data 的 UTF-8 字节
        byte[] dataBytes;
        if (data instanceof CharSequence) {
            dataBytes = data.toString().getBytes(StandardCharsets.UTF_8);
        } else {
            dataBytes = encodeEventDataAsBytes(data);
        }

        // 写入 "data:"
        os.write(DATA_PREFIX);

        // 替换 data 中的 \n 为 \ndata:（字节级扫描）
        int start = 0;
        for (int i = 0; i < dataBytes.length; i++) {
            if (dataBytes[i] == '\n') {
                os.write(dataBytes, start, i - start);
                os.write(NEWLINE_DATA);
                start = i + 1;
            }
        }
        os.write(dataBytes, start, dataBytes.length - start);

        // 以 \n\n 终止
        os.write(TERMINATOR);

        return os.toByteArray();
    }

    protected String encodeEventData(Object data) {
        throw new UnsupportedOperationException();
    }

    /**
     * 将数据编码为 UTF-8 字节，供 {@link #encodeToBytes(Object)} 使用。
     */
    protected byte[] encodeEventDataAsBytes(Object data) throws IOException {
        throw new UnsupportedOperationException();
    }

    protected void writeField(String fieldName, Object fieldValue, StringBuilder sb) {
        sb.append(fieldName).append(':').append(fieldValue).append("\n");
    }

    private void writeFieldBytes(ByteArrayOutputStream os, byte[] prefix, byte[] value) {
        os.write(prefix, 0, prefix.length);
        os.write(value, 0, value.length);
        os.write(NEWLINE, 0, NEWLINE.length);
    }

    @Override
    protected int getMaxFlushBytes() {
        return 1024 * 4;
    }

}

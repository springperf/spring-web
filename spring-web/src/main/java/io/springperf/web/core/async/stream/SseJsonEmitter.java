package io.springperf.web.core.async.stream;

import java.io.ByteArrayOutputStream;
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

    /**
     * 先编码到内存，再按 {@code data:} 续行写出。
     * <p>
     * 不能直接写给 {@code out}：转换器是否**美化输出**决定 JSON 里有没有裸 LF，而 SSE 字段值不得含 LF —— 一个裸 LF 会让客户端按规范**丢弃整条事件**（静默丢数据）。基类对非
     * {@code CharSequence} 数据不做续行， 故此处自行处理（代价是每事件一次内存拷贝，换来的是不会静默丢事件）。
     * </p>
     */
    @Override
    protected void encodeEventDataAsBytes(Object data, OutputStream out) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        jsonConverter.toJson(buffer, data);
        writeDataBytes(out, buffer.toByteArray());
    }
}

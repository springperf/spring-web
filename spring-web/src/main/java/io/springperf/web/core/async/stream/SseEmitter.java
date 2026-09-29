package io.springperf.web.core.async.stream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.ServerHttpResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static io.springperf.web.util.IoUtils.writeCharSequence;

public class SseEmitter extends StreamEmitter<Object> {

    private static final byte[] DATA_PREFIX = "data:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEWLINE_DATA = "\ndata:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEWLINE_COMMENT = "\n:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TERMINATOR = "\n\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NEWLINE = "\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FIELD_ID = "id:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FIELD_EVENT = "event:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FIELD_RETRY = "retry:".getBytes(StandardCharsets.UTF_8);

    public SseEmitter() {
        super();
    }

    public SseEmitter(Long timeout) {
        super(timeout);
    }

    public SseEmitter(boolean earlyEncode) {
        super(earlyEncode);
    }

    public SseEmitter(Long timeout, boolean earlyEncode) {
        super(timeout, earlyEncode);
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
    public void encode(Object data, OutputStream out) throws IOException {
        if (data instanceof ServerSentEvent sse) {
            encodeServerSentEvent(sse, out);
        } else {
            encodeData(data, out);
        }
    }

    /**
     * 编码 SSE data 字段。
     * <ul>
     * <li>{@link CharSequence} 数据：经由 {@code getBytes()} 编码为 byte[] 后扫描 {@code \n} 并用 {@code \ndata:} 续行，满足 SSE
     * 协议要求。</li>
     * <li>非 {@link CharSequence} 数据：直接通过 {@link #encodeEventDataAsBytes(Object, OutputStream)} 写入，不做 {@code \n} 扫描。非
     * CharSequence 数据（如 JSON）通常不含裸 {@code \n}， 若子类数据可能包含 {@code \n}，需自行在 {@code encodeEventDataAsBytes} 中处理续行。</li>
     * </ul>
     */
    protected void encodeData(Object data, OutputStream out) throws IOException {
        if (data == null) {
            // SSE 规范要求事件以 data: 字段开头、以空行结束。旧实现只写裸 \n，
            // 客户端既解析不出 data 字段也判定不出事件边界，等同丢事件。
            out.write(DATA_PREFIX);
            out.write(TERMINATOR);
            return;
        }
        out.write(DATA_PREFIX);
        if (data instanceof CharSequence) {
            byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
            writeBytesWithNewline(out, bytes, NEWLINE_DATA);
        } else {
            encodeEventDataAsBytes(data, out);
        }
        out.write(TERMINATOR);
    }

    /**
     * 编码 {@link ServerSentEvent}。字段值含 LF 时的分工（不要误判为漏改）：
     * <ul>
     * <li>{@code id} / {@code event}：{@code ServerSentEvent} 的 builder **在构造期就拒绝**含 LF 的值
     * （{@code illegal character}），故此处原样写出是安全的；</li>
     * <li>{@code data}：自由文本，builder 不校验，故经 {@link #writeBytesWithNewline} 按 LF 续行；</li>
     * <li>{@code comment}：同理自行续行。</li>
     * </ul>
     */
    protected void encodeServerSentEvent(ServerSentEvent sse, OutputStream out) throws IOException {
        String id = sse.id();
        if (id != null) {
            out.write(FIELD_ID);
            writeCharSequence(out, id, StandardCharsets.UTF_8);
            out.write(NEWLINE);
        }
        String event = sse.event();
        if (event != null) {
            out.write(FIELD_EVENT);
            writeCharSequence(out, event, StandardCharsets.UTF_8);
            out.write(NEWLINE);
        }
        Duration retry = sse.retry();
        if (retry != null) {
            out.write(FIELD_RETRY);
            writeCharSequence(out, Long.toString(retry.toMillis()), StandardCharsets.UTF_8);
            out.write(NEWLINE);
        }
        String comment = sse.comment();
        if (comment != null) {
            out.write(':');
            byte[] commentBytes = comment.getBytes(StandardCharsets.UTF_8);
            writeBytesWithNewline(out, commentBytes, NEWLINE_COMMENT);
            out.write(NEWLINE);
        }
        encodeData(sse.data(), out);
    }

    /**
     * 以 {@code data:} 续行规则写出一段**已编码**的数据，供 {@link #encodeEventDataAsBytes} 的子类实现使用。
     * <p>
     * 存在的理由：SSE 字段值不得含 LF，而子类写出的内容可能带裸 LF（典型：美化输出的 JSON）；基类对非 {@code CharSequence}
     * 数据不做续行，子类若不处理，客户端按规范会**丢弃整条事件**（静默丢数据）。
     * </p>
     */
    protected static void writeDataBytes(OutputStream out, byte[] bytes) throws IOException {
        writeBytesWithNewline(out, bytes, NEWLINE_DATA);
    }

    private static void writeBytesWithNewline(OutputStream out, byte[] bytes, byte[] continuation) throws IOException {
        int start = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {
                out.write(bytes, start, i - start);
                out.write(continuation);
                start = i + 1;
            }
        }
        out.write(bytes, start, bytes.length - start);
    }

    /**
     * 子类可重写此方法来自定义数据序列化（如 {@link SseJsonEmitter}）。
     * <p>
     * 注意：此方法写入的数据不会经过 {@code \n} 续行处理。 若子类数据可能包含 {@code \n}，需自行在此方法中处理续行逻辑。
     */
    protected void encodeEventDataAsBytes(Object data, OutputStream out) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    protected int getMaxFlushBytes() {
        return 1024 * 4;
    }

}

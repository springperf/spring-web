package io.springperf.web.websocket.jsr;

import io.springperf.web.websocket.server.NettyWebSocketSession;
import jakarta.websocket.EncodeException;
import jakarta.websocket.Encoder;
import jakarta.websocket.RemoteEndpoint;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * JSR-356 {@link RemoteEndpoint.Basic} 实现，委托 Spring {@link WebSocketSession} 发送。
 * <p>
 * 消息体为 {@code String}（文本）、{@code ByteBuffer}（二进制）或 POJO（经 {@link Encoder} 编码，文本/二进制任选其一）。 分片发送通过
 * {@code sendText}/{@code sendBinary} 的 {@code last} 参数透传：{@code false} 表示该分片不是消息的最后一段， 由 {@link NettyWebSocketSession}
 * 落成非 final 的 WebSocket 帧。流式发送（{@link #getSendStream()} / {@link #getSendWriter()}）在容器侧缓冲， {@code flush()} /
 * {@code close()} 时把已写入内容作为一条完整消息发出。
 * </p>
 * <p>
 * 注意：{@code last=false} 的分片必须以 {@code last=true} 收尾，否则客户端会一直等待后续帧（WebSocket 语义如此）。
 * </p>
 * <p>
 * 同步语义：本接口是 {@code Basic}，规范要求 sendXxx「返回时消息已发出」。业务线程调用时会等待帧真正写入（EventLoop 线程内调用则直接写， 不等待——等待自己排队中的任务会死锁）；需要非阻塞请用
 * {@code getAsyncRemote()}。二进制发送成功后按规范把调用方 {@code ByteBuffer} 的 position 推进到 limit。
 * </p>
 *
 * @author huangcanda
 *
 * @since 3.5.6
 */
public class JsrRemoteEndpointBasic implements RemoteEndpoint.Basic {

    private final JsrWebSocketSession session;
    private final JsrCodecRegistry codecRegistry;

    /**
     * JSR-356 批处理开关。开启后 {@code sendXxx} 只把帧写入 Netty outbound buffer， 直到 {@link #flushBatch()}（或关闭批处理）才落网，
     * 多条小消息因此合并为一次系统调用。
     * <p>
     * volatile：开关可能由非发送线程设置，而发送线程必须立刻看到新值，否则「关闭批处理」后仍有消息被暂存。
     * </p>
     */
    private volatile boolean batchingAllowed;

    public JsrRemoteEndpointBasic(JsrWebSocketSession session, JsrCodecRegistry codecRegistry) {
        this.session = session;
        this.codecRegistry = codecRegistry;
    }

    @Override
    public void sendText(String text) throws IOException {
        session.sendSync(new TextMessage(text), !batchingAllowed);
    }

    /**
     * 同步发送二进制消息，并按规范把 {@code data} 的 position 推进到 limit。
     * <p>
     * jakarta.websocket 契约：「It is not safe for other threads to use the ByteBuffer until the sending of this message is
     * complete. If the sending completes successfully, the buffer's limit will be unchanged and the buffer's position
     * will be equal to the limit.」 底层用 {@code duplicate()} 读取，不会替调用方推进，因此必须在这里补上——依赖该副作用的代码（循环发送、池化回收）行为才与 其他实现一致。
     * </p>
     */
    @Override
    public void sendBinary(ByteBuffer data) throws IOException {
        session.sendSync(new BinaryMessage(data), !batchingAllowed);
        session.markBufferConsumed(data);
    }

    @Override
    public void sendText(String partialMessage, boolean isLast) throws IOException {
        session.sendSync(new TextMessage(partialMessage, isLast), !batchingAllowed);
    }

    /** 分片发送同样按规范推进 position（契约对 partial 消息一视同仁）。 */
    @Override
    public void sendBinary(ByteBuffer partialByte, boolean isLast) throws IOException {
        session.sendSync(new BinaryMessage(partialByte, isLast), !batchingAllowed);
        session.markBufferConsumed(partialByte);
    }

    @Override
    public OutputStream getSendStream() throws IOException {
        return new BufferedSendStream();
    }

    @Override
    public Writer getSendWriter() throws IOException {
        return new BufferedSendWriter();
    }

    @Override
    public void sendObject(Object data) throws IOException, EncodeException {
        JsrCodecRegistry.EncodedPayload payload = codecRegistry.encode(data, true);
        if (payload.isText()) {
            session.sendSync(new TextMessage(payload.getText()), !batchingAllowed);
        } else {
            session.sendSync(new BinaryMessage(payload.getBinary()), !batchingAllowed);
        }
    }

    @Override
    public void setBatchingAllowed(boolean allowed) throws IOException {
        if (batchingAllowed && !allowed) {
            // 由开转关：已写入但未 flush 的帧必须在返回前刷出，否则会滞留到下一次 flush 或连接关闭
            session.flushBatch();
        }
        batchingAllowed = allowed;
    }

    @Override
    public boolean getBatchingAllowed() {
        return batchingAllowed;
    }

    @Override
    public void flushBatch() throws IOException {
        session.flushBatch();
    }

    @Override
    public void sendPing(ByteBuffer applicationData) throws IOException, IllegalArgumentException {
        JsrWebSocketSession.checkControlFramePayload(applicationData);
        // 控制帧不受批处理影响：ping/pong 直接影响对端保活与空闲检测，滞留到下一次 flush 会被当作心跳丢失
        session.sendSync(new org.springframework.web.socket.PingMessage(applicationData), true);
    }

    @Override
    public void sendPong(ByteBuffer applicationData) throws IOException, IllegalArgumentException {
        JsrWebSocketSession.checkControlFramePayload(applicationData);
        session.sendSync(new org.springframework.web.socket.PongMessage(applicationData), true);
    }

    /**
     * JSR-356 的流式二进制发送：容器侧缓冲，{@code flush()} / {@code close()} 时把已写入的字节作为**一条完整消息**发出。
     * <p>
     * 业务因此可以「边算边写」，不必先拼出整块 byte[]；内存上限由调用方的 flush 频率决定。
     * </p>
     */
    private final class BufferedSendStream extends OutputStream {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        private boolean closed;

        @Override
        public void write(int b) throws IOException {
            ensureOpen();
            buffer.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            ensureOpen();
            buffer.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            ensureOpen();
            sendBuffer();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            sendBuffer();
            closed = true;
        }

        private void sendBuffer() throws IOException {
            if (buffer.size() == 0) {
                return;
            }
            byte[] bytes = buffer.toByteArray();
            buffer.reset();
            sendBinary(ByteBuffer.wrap(bytes));
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("OutputStream is closed");
            }
        }
    }

    /**
     * JSR-356 的流式文本发送：{@link OutputStreamWriter} 维护 UTF-8 编码状态（多字节字符/代理对被 write 边界切开也不会坏）， 缓冲到 {@code flush()} /
     * {@code close()} 时整条发出。
     */
    private final class BufferedSendWriter extends Writer {

        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        private final Writer encoder = new OutputStreamWriter(buffer, StandardCharsets.UTF_8);
        private boolean closed;

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            ensureOpen();
            encoder.write(cbuf, off, len);
        }

        @Override
        public void flush() throws IOException {
            ensureOpen();
            encoder.flush();
            sendBuffer();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            // 先让编码器把残余字符（含未完成的代理对）落进缓冲，再整条发出
            encoder.close();
            sendBuffer();
            closed = true;
        }

        private void sendBuffer() throws IOException {
            if (buffer.size() == 0) {
                return;
            }
            String text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            buffer.reset();
            sendText(text);
        }

        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("Writer is closed");
            }
        }
    }
}

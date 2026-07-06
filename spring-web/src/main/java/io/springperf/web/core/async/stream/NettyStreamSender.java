package io.springperf.web.core.async.stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.internal.shaded.org.jctools.queues.MpscUnboundedArrayQueue;
import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.http.NettyServerHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public class NettyStreamSender implements StreamSender {

    protected static final Logger log = LoggerFactory.getLogger(NettyStreamSender.class);

    protected static final ConcurrentMap<Charset, Float> charsetToMaxBytesPerChar = new ConcurrentHashMap<>(3);

    private final Channel channel;
    private final EventExecutor eventLoop;
    private final ByteBufAllocator bufAllocator;
    private final StreamEmitter emitter;
    private final NettyServerHttpResponse resp;
    private final MpscUnboundedArrayQueue<ByteBuf> queue;

    /**
     * 单次 drain 最多写多少条，防止 EventLoop 饿死
     */
    private final int maxFlushBytes;

    private final AtomicInteger wip = new AtomicInteger(0);
    private volatile boolean completed;

    private boolean lastHttpContentWritten;

    private volatile boolean closeChannelOnComplete = false;
    private final ChannelFutureListener completeListener = future -> {
        if (future.isSuccess()) {
            onCompleteSuccess();
        } else {
            onCompleteError(future.cause());
        }
    };

    public NettyStreamSender(StreamEmitter emitter, PerfAsyncWebRequest asyncWebRequest) {
        this.emitter = emitter;
        this.resp = (NettyServerHttpResponse) asyncWebRequest.getNativeResponse();
        ChannelHandlerContext ctx = this.resp.getCtx();
        this.channel = ctx.channel();
        this.eventLoop = ctx.executor();
        this.bufAllocator = ctx.alloc();
        this.queue = new MpscUnboundedArrayQueue<>(64);
        this.maxFlushBytes = emitter.getMaxFlushBytes();
        this.resp.setWritableCallback(this::scheduleDrain);
    }

    @Override
    public void send(Object data) throws IOException {
        if (!channel.isActive()) {
            throw new IOException("Stream closed");
        }
        if (completed) {
            throw new IOException("Stream completed");
        }
        ByteBuf buf;
        if (emitter.encodeToString) {
            CharSequence charSequence = emitter.encodeToString(data);
            if (charSequence == null || charSequence.length() == 0) {
                return;
            }
            buf = writeCharSequence(charSequence);
        } else {
            byte[] bytes = emitter.encodeToBytes(data);
            if (bytes == null || bytes.length == 0) {
                return;
            }
            buf = bufAllocator.buffer(bytes.length);
            buf.writeBytes(bytes);
        }

        if (!queue.offer(buf)) {
            log.warn("[SSE] queue full, releasing buf");
            buf.release();
            return;
        }
        scheduleDrain();
    }

    @Override
    public void complete(boolean closeChannelOnComplete, Throwable failure) {
        this.closeChannelOnComplete = closeChannelOnComplete;
        this.completed = true;
        scheduleDrain();
    }

    @Override
    public int queueSize() {
        return queue.size();
    }

    protected void scheduleDrain() {
        if (eventLoop.inEventLoop()) {
            drain();
        } else if (wip.getAndIncrement() == 0) {
            eventLoop.execute(this::drain);
        }
    }

    /**
     * 只能在 EventLoop 线程执行
     */
    protected void drain() {
        int missed = 1;
        for (; ; ) {
            int writtenBytes = 0;
            while (channel.isWritable()) {
                ByteBuf buf = queue.peek();
                if (buf == null) {
                    break;
                }
                int size = buf.readableBytes();
                if (writtenBytes > 0 && writtenBytes + size > maxFlushBytes) {
                    break;
                }
                buf = queue.poll();
                ChannelFuture f = channel.write(new DefaultHttpContent(buf));
                resp.addRespEventListener(f, false);
                writtenBytes += size;
            }
            if (writtenBytes > 0) {
                channel.flush();
            }
            missed = wip.addAndGet(-missed);
            if (missed == 0) {
                break;
            }
        }
        // wip 归零但队列仍有数据：重调度 drain，防止数据永远留在队列中
        if (!queue.isEmpty()) {
            if (channel.isWritable() && wip.compareAndSet(0, 1)) {
                eventLoop.execute(this::drain);
            } else if (completed && !lastHttpContentWritten) {
                // channel 不可写但 complete 已调用：强制重调度 drain，
                if (wip.compareAndSet(0, 1)) {
                    eventLoop.execute(this::drain);
                }
            }
            return;
        }
        if (completed && !lastHttpContentWritten) {
            lastHttpContentWritten = true;
            onAllDataWritten();
        }
    }

    protected void onAllDataWritten() {
        this.resp.setWritableCallback(null);
        ChannelFuture f = channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        resp.addRespEventListener(f, true);
        f.addListener(completeListener);
    }

    protected void onCompleteSuccess() {
        this.resp.setTimeout(null, -1);
        if (closeChannelOnComplete || !resp.isKeepAlive()) {
            this.channel.close();
        }
    }

    protected void onCompleteError(Throwable t) {
        this.resp.setTimeout(null, -1);
        log.warn("[SSE] write ERROR: {}", t.getMessage(), t);
        if (closeChannelOnComplete || !resp.isKeepAlive()) {
            this.channel.close();
        }
    }

    protected ByteBuf writeCharSequence(CharSequence charSequence) {
        Charset charset = resp.getCharacterEncoding();
        int capacity = calculateCapacity(charSequence, charset);
        ByteBuf byteBuf = bufAllocator.buffer(capacity);
        try {
            if (StandardCharsets.UTF_8.equals(charset)) {
                ByteBufUtil.writeUtf8(byteBuf, charSequence);
            } else if (StandardCharsets.US_ASCII.equals(charset)) {
                ByteBufUtil.writeAscii(byteBuf, charSequence);
            } else {
                CharsetEncoder charsetEncoder = charset.newEncoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
                CharBuffer inBuffer = CharBuffer.wrap(charSequence);
                int estimatedSize = (int) (inBuffer.remaining() * charsetEncoder.averageBytesPerChar());
                ByteBuffer outBuffer = byteBuf.ensureWritable(estimatedSize).nioBuffer(byteBuf.writerIndex(), byteBuf.writableBytes());
                while (true) {
                    CoderResult cr = (inBuffer.hasRemaining() ? charsetEncoder.encode(inBuffer, outBuffer, true) : CoderResult.UNDERFLOW);
                    if (cr.isUnderflow()) {
                        cr = charsetEncoder.flush(outBuffer);
                    }
                    if (cr.isUnderflow()) {
                        break;
                    }
                    if (cr.isOverflow()) {
                        byteBuf.writerIndex(byteBuf.writerIndex() + outBuffer.position());
                        int maximumSize = (int) (inBuffer.remaining() * charsetEncoder.maxBytesPerChar());
                        byteBuf.ensureWritable(maximumSize);
                        outBuffer = byteBuf.nioBuffer(byteBuf.writerIndex(), byteBuf.writableBytes());
                    }
                }
                byteBuf.writerIndex(byteBuf.writerIndex() + outBuffer.position());
            }
            return byteBuf;
        } catch (Throwable t) {
            byteBuf.release();
            throw t;
        }
    }

    protected int calculateCapacity(CharSequence sequence, Charset charset) {
        float maxBytesPerChar = this.charsetToMaxBytesPerChar
                .computeIfAbsent(charset, cs -> cs.newEncoder().maxBytesPerChar());
        float maxBytesForSequence = sequence.length() * maxBytesPerChar;
        return (int) Math.ceil(maxBytesForSequence);
    }
}
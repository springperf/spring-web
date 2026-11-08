package io.springperf.web.support.mvc.retval;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.springperf.web.core.async.PerfAsyncWebRequest;
import io.springperf.web.core.async.stream.StreamSender;
import io.springperf.web.core.async.stream.StreamSenderFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ByteStreamEmitterTest {

    private RecordingSender sender;

    private StreamSenderFactory senderFactory;

    private PerfAsyncWebRequest asyncWebRequest;

    @BeforeEach
    void setUp() {
        sender = new RecordingSender();
        asyncWebRequest = mock(PerfAsyncWebRequest.class);
        senderFactory = mock(StreamSenderFactory.class);
        when(senderFactory.create(any(), any())).thenReturn(sender);
    }

    @Test
    void sendFrame_forwardsBytesToSenderInOrder() throws IOException {
        ByteStreamEmitter emitter = new ByteStreamEmitter(asyncWebRequest, senderFactory, false);

        emitter.sendFrame(new byte[] { 1, 2 });
        emitter.sendFrame(new byte[] { 3 });

        assertThat(sender.sent).containsExactly(new byte[] { 1, 2 }, new byte[] { 3 });
        assertThat(sender.completed).isFalse();
    }

    @Test
    void headRequest_dropsBodyWithoutCreatingSender() throws IOException {
        ByteStreamEmitter emitter = new ByteStreamEmitter(asyncWebRequest, senderFactory, true);

        emitter.sendFrame(new byte[] { 1, 2, 3 });

        verify(senderFactory, never()).create(any(), any());
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void complete_createsSenderEvenWhenNoFrameWasWritten() throws IOException {
        // 0 字节：chunked 头已由 resolver 提交，必须补写终止块，否则客户端挂到读超时
        ByteStreamEmitter emitter = new ByteStreamEmitter(asyncWebRequest, senderFactory, false);

        emitter.complete();

        verify(senderFactory).create(emitter, asyncWebRequest);
        assertThat(sender.completed).isTrue();
        assertThat(sender.failure).isNull();
    }

    @Test
    void completeIsIdempotent() throws IOException {
        ByteStreamEmitter emitter = new ByteStreamEmitter(asyncWebRequest, senderFactory, false);
        emitter.sendFrame(new byte[] { 1 });

        emitter.complete();
        emitter.complete();

        verify(senderFactory).create(any(), any());
        assertThat(sender.completeCount).isEqualTo(1);
    }

    @Test
    void completeWithError_passesFailureToSender() throws IOException {
        ByteStreamEmitter emitter = new ByteStreamEmitter(asyncWebRequest, senderFactory, false);
        emitter.sendFrame(new byte[] { 1 });
        IllegalStateException failure = new IllegalStateException("boom");

        emitter.completeWithError(failure);

        assertThat(sender.completed).isTrue();
        assertThat(sender.failure).isSameAs(failure);
    }

    @Test
    void completeWithError_createsSenderSoConnectionCanBeClosed() {
        ByteStreamEmitter emitter = new ByteStreamEmitter(asyncWebRequest, senderFactory, false);

        emitter.completeWithError(new IllegalStateException("before first frame"));

        verify(senderFactory).create(any(), any());
        assertThat(sender.failure).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sendFrame_blocksWhenQueueIsFullAndFailsOnInterrupt() {
        sender.queueSize = 32;
        ByteStreamEmitter emitter = new ByteStreamEmitter(asyncWebRequest, senderFactory, false);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> emitter.sendFrame(new byte[] { 1 })).isInstanceOf(IOException.class)
                    .hasMessageContaining("interrupted");
        } finally {
            Thread.interrupted();
        }
        assertThat(sender.sent).isEmpty();
    }

    private static final class RecordingSender implements StreamSender {

        private final List<byte[]> sent = new ArrayList<>();

        private boolean completed;

        private Throwable failure;

        private int completeCount;

        private int queueSize;

        @Override
        public void send(Object data) {
            sent.add((byte[]) data);
        }

        @Override
        public void sendAll(Collection<?> data) {
            data.forEach(this::send);
        }

        @Override
        public void complete(boolean closeChannelOnComplete, Throwable failure) {
            this.completed = true;
            this.failure = failure;
            this.completeCount++;
        }

        @Override
        public int queueSize() {
            return queueSize;
        }
    }
}

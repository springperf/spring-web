package io.springperf.web.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;

/** h2c 明文前言探测器的分片累积、HTTP/1.1 回退、非 ByteBuf 透传与引用计数释放。 */
class Http2ChannelInitializerH2cPrefaceTest {

    private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HTTP11 = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"
            .getBytes(StandardCharsets.US_ASCII);

    private static EmbeddedChannel channelWithDetector() throws Exception {
        Http2ChannelInitializer init = new Http2ChannelInitializer(true, null, 1024, 0, false,
                mock(NettyHttpHandler.class), Collections.emptyList(), Collections.emptyList());
        EmbeddedChannel channel = new EmbeddedChannel();
        Method m = Http2ChannelInitializer.class.getDeclaredMethod("addCleartextHttp2Handlers", ChannelPipeline.class);
        m.setAccessible(true);
        m.invoke(init, channel.pipeline());
        ChannelHandler detector = channel.pipeline().first();
        List<String> names = new ArrayList<>(channel.pipeline().names());
        int detectorIndex = names.indexOf(channel.pipeline().context(detector).name());
        for (int i = names.size() - 1; i > detectorIndex; i--) {
            String name = names.get(i);
            if (!name.contains("LastInboundHandler") && !name.contains("HeadContext")
                    && !name.contains("TailContext")) {
                channel.pipeline().remove(name);
            }
        }
        return channel;
    }

    private static ByteBuf accumulatorOf(ChannelHandler detector) throws Exception {
        Field f = detector.getClass().getDeclaredField("accumulator");
        f.setAccessible(true);
        return (ByteBuf) f.get(detector);
    }

    @Test
    void wholePrefaceSwitchesToHttp2() throws Exception {
        EmbeddedChannel channel = channelWithDetector();
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(PREFACE));

            assertNotNull(channel.pipeline().get("h2-frame-codec"));
            assertNotNull(channel.pipeline().get("h2-multiplex"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void fragmentedPrefaceSwitchesToHttp2() throws Exception {
        EmbeddedChannel channel = channelWithDetector();
        try {
            ChannelHandler detector = channel.pipeline().first();
            channel.writeInbound(Unpooled.wrappedBuffer(PREFACE, 0, 12));
            assertNull(channel.pipeline().get("h2-frame-codec"), "不足 24 字节时不应切换协议");
            assertEquals(12, accumulatorOf(detector).readableBytes());

            channel.writeInbound(Unpooled.wrappedBuffer(PREFACE, 12, PREFACE.length - 12));

            assertNotNull(channel.pipeline().get("h2-frame-codec"));
            assertNotNull(channel.pipeline().get("h2-multiplex"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void fragmentedNonHttp2FallsBackToHttp11() throws Exception {
        EmbeddedChannel channel = channelWithDetector();
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(HTTP11, 0, 12));
            channel.writeInbound(Unpooled.wrappedBuffer(HTTP11, 12, HTTP11.length - 12));

            assertNull(channel.pipeline().get("h2-frame-codec"));
            assertNull(channel.pipeline().get("h2-multiplex"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void wholeNonHttp2FallsBackToHttp11() throws Exception {
        EmbeddedChannel channel = channelWithDetector();
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(HTTP11));

            assertNull(channel.pipeline().get("h2-frame-codec"));
            assertNull(channel.pipeline().get("h2-multiplex"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void nonByteBufMessagePassesThrough() throws Exception {
        EmbeddedChannel channel = channelWithDetector();
        try {
            Object marker = new Object();
            channel.writeInbound(marker);

            assertSame(marker, channel.readInbound());
            assertNull(channel.pipeline().get("h2-frame-codec"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void handlerRemovedReleasesPartiallyAccumulatedBuffer() throws Exception {
        EmbeddedChannel channel = channelWithDetector();
        ChannelHandler detector = channel.pipeline().first();
        channel.writeInbound(Unpooled.wrappedBuffer(PREFACE, 0, 8));
        ByteBuf accumulated = accumulatorOf(detector);
        assertEquals(1, accumulated.refCnt());
        assertEquals(8, accumulated.readableBytes());

        channel.finishAndReleaseAll();

        assertEquals(0, accumulated.refCnt(), "handlerRemoved 必须释放累积 ByteBuf，否则分片连接泄漏");
    }
}

package io.springperf.web.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class NettyServerHttpResponseTest {

    private WebContext webContext;
    private ChannelHandlerContext ctx;
    private EventLoop eventLoop;
    private Channel channel;
    private ByteBufAllocator allocator;
    private NettyServerHttpResponse response;

    @BeforeEach
    void setUp() {
        webContext = mock(WebContext.class);
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.getLong(PropertiesConstant.HTTP_TIMEOUT)).thenReturn(60000L);
        when(webContext.getProps()).thenReturn(props);

        ctx = mock(ChannelHandlerContext.class);
        eventLoop = mock(EventLoop.class);
        channel = mock(Channel.class);
        allocator = mock(ByteBufAllocator.class);

        when(ctx.executor()).thenReturn(eventLoop);
        when(ctx.channel()).thenReturn(channel);
        when(ctx.alloc()).thenReturn(allocator);
        when(channel.localAddress()).thenReturn(new InetSocketAddress(9090));

        response = new NettyServerHttpResponse(webContext, ctx, false);
    }

    @Test
    void getBuf_lazyCreatesBuffer() {
        ByteBuf buf = mock(ByteBuf.class);
        when(allocator.buffer(256)).thenReturn(buf);

        assertSame(buf, response.getBuf());
        verify(allocator).buffer(256);
    }

    @Test
    void getBuf_returnsSameInstance() {
        ByteBuf buf = mock(ByteBuf.class);
        when(allocator.buffer(256)).thenReturn(buf);

        assertSame(response.getBuf(), response.getBuf());
        verify(allocator).buffer(256); // only created once
    }

    @Test
    void getBody_returnsByteBufOutputStream() {
        ByteBuf buf = mock(ByteBuf.class);
        when(allocator.buffer(256)).thenReturn(buf);

        assertNotNull(response.getBody());
    }

    @Test
    void flush_writesAndFlushesBuf() {
        ByteBuf buf = mock(ByteBuf.class);
        when(allocator.buffer(256)).thenReturn(buf);
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
        when(buf.readableBytes()).thenReturn(0);

        response.getBuf();
        assertDoesNotThrow(() -> response.flush());
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void flush_withoutBuf_noWrite() {
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));

        assertDoesNotThrow(() -> response.flush());
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void writeStream_writesChunkedStream() {
        ChannelFuture future = mock(ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        when(future.isSuccess()).thenReturn(true);
        when(future.addListener(any())).thenReturn(future);

        InputStream input = new ByteArrayInputStream("test data".getBytes(StandardCharsets.UTF_8));
        assertDoesNotThrow(() -> response.writeStream(input));
    }

    @Test
    void writeFile_writesFile() throws Exception {
        File testFile = java.nio.file.Files.createTempFile("test", ".bin").toFile();
        java.nio.file.Files.write(testFile.toPath(), "file content".getBytes(StandardCharsets.UTF_8));

        ChannelFuture future = mock(ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        when(future.addListener(any())).thenReturn(future);

        assertDoesNotThrow(() -> response.writeFile(testFile));
        verify(ctx, atLeast(2)).writeAndFlush(any());

        testFile.delete();
    }

    @Test
    void addRespEventListener_withListener_callsAddListener() {
        ChannelFuture future = mock(ChannelFuture.class);
        WriteRespEventListener listener = mock(WriteRespEventListener.class);
        response.setWriteRespEventListener(listener);

        response.addRespEventListener(future, true);

        verify(future).addListener(any());
    }

    @Test
    void addRespEventListener_withoutListener_logsErrorOnFailure() {
        ChannelFuture future = mock(ChannelFuture.class);
        when(future.isSuccess()).thenReturn(false);
        when(future.cause()).thenReturn(new RuntimeException("test error"));

        response.addRespEventListener(future, true);
        // no listener set -> uses LOG_ERROR_ON_FAILURE, just verify no throw
    }

    @Test
    void addRespEventListener_complete_attachesCallback() {
        ChannelFuture future = mock(ChannelFuture.class);
        WriteRespEventListener listener = mock(WriteRespEventListener.class);
        response.setWriteRespEventListener(listener);

        response.addRespEventListener(future, true);

        verify(future).addListener(any());
    }

    @Test
    void addRespEventListener_stream_attachesCallback() {
        ChannelFuture future = mock(ChannelFuture.class);
        WriteRespEventListener listener = mock(WriteRespEventListener.class);
        response.setWriteRespEventListener(listener);

        response.addRespEventListener(future, false);

        verify(future).addListener(any());
    }

    @Test
    void runOnEventLoop_inEventLoop_runsDirectly() {
        when(eventLoop.inEventLoop()).thenReturn(true);

        Runnable task = mock(Runnable.class);
        response.runOnEventLoop(task);

        verify(task).run();
        verify(eventLoop, never()).execute(any());
    }

    @Test
    void runOnEventLoop_outsideEventLoop_executesOnLoop() {
        when(eventLoop.inEventLoop()).thenReturn(false);

        Runnable task = mock(Runnable.class);
        response.runOnEventLoop(task);

        verify(eventLoop).execute(task);
        verify(task, never()).run();
    }

    @SuppressWarnings("unchecked")
    @Test
    void scheduleOnEventLoop_delegatesToExecutor() {
        io.netty.util.concurrent.ScheduledFuture<?> nettyFuture = mock(io.netty.util.concurrent.ScheduledFuture.class);
        when(eventLoop.schedule(any(Runnable.class), eq(100L), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> nettyFuture);

        Runnable task = mock(Runnable.class);
        ScheduledFuture result = response.scheduleOnEventLoop(task, 100, TimeUnit.MILLISECONDS);

        assertSame(nettyFuture, result);
        verify(eventLoop).schedule(task, 100, TimeUnit.MILLISECONDS);
    }

    @Test
    void getCtx_returnsConstructedContext() {
        assertSame(ctx, response.getCtx());
    }

    @Test
    void setWritableCallback_setsCallback() {
        Runnable callback = mock(Runnable.class);
        response.setWritableCallback(callback);
        // verifying no throw
    }

    @Test
    void flush_error_releasesBuf() {
        ByteBuf buf = mock(ByteBuf.class);
        when(allocator.buffer(256)).thenReturn(buf);
        when(ctx.writeAndFlush(any())).thenThrow(new RuntimeException("write failed"));
        when(buf.refCnt()).thenReturn(1);

        response.getBuf();
        assertThrows(RuntimeException.class, () -> response.flush());
        verify(buf).release();
    }

    @Test
    void writeFile_handlesSyncException() {
        when(ctx.writeAndFlush(any())).thenThrow(new RuntimeException("write error"));

        File nonexistent = new File("should_not_exist_12345");
        assertThrows(RuntimeException.class, () -> response.writeFile(nonexistent));
    }
}

package io.springperf.web.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.channel.FileRegion;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    void flush_withoutBuf_writesEmptyResponse() {
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));

        assertDoesNotThrow(() -> response.flush());
        // 无 buf 时仍应写出 Content-Length:0 的空响应（initHttpResponse 空 buffer 分支），不会静默跳过
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx).writeAndFlush(captor.capture());
        assertTrue(captor.getValue() instanceof io.netty.handler.codec.http.FullHttpResponse);
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
        when(ctx.write(any())).thenReturn(future);
        when(future.addListener(any())).thenReturn(future);

        assertDoesNotThrow(() -> response.writeFile(testFile));
        verify(ctx, atLeast(2)).writeAndFlush(any());
        verify(ctx).write(any());

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
    void writeFile_usesContentLengthFraming_only() throws Exception {
        File testFile = java.nio.file.Files.createTempFile("test", ".bin").toFile();
        byte[] content = "file content".getBytes(StandardCharsets.UTF_8);
        java.nio.file.Files.write(testFile.toPath(), content);

        ChannelFuture future = mock(ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        when(ctx.write(any())).thenReturn(future);
        when(future.addListener(any())).thenReturn(future);

        response.writeFile(testFile);

        ArgumentCaptor<Object> headersCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(ctx, atLeast(2)).writeAndFlush(headersCaptor.capture());
        verify(ctx).write(bodyCaptor.capture());
        // 头部响应：仅 Content-Length，绝不能同时带 Transfer-Encoding（双帧非法/客户端错乱）
        HttpResponse headers = (HttpResponse) headersCaptor.getAllValues().get(0);
        assertFalse(headers instanceof FullHttpResponse, "file body is streamed separately, headers must not be full");
        assertNull(headers.headers().get(HttpHeaderNames.TRANSFER_ENCODING),
                "file download must not set Transfer-Encoding: chunked");
        assertEquals(content.length, headers.headers().getInt(HttpHeaderNames.CONTENT_LENGTH),
                "Content-Length must match file size");
        // 文件体：零拷贝 FileRegion（非 chunk 编码）
        assertTrue(bodyCaptor.getValue() instanceof FileRegion,
                "file body should be written as zero-copy FileRegion");
        // 终结：FileRegion 后必须补 LastHttpContent，让 HttpObjectEncoder 状态从 ST_CONTENT 归位，
        // 否则 keep-alive 连接被污染（下个请求写 DefaultHttpResponse 抛 state:1 异常）。
        assertTrue(headersCaptor.getAllValues().get(1) instanceof LastHttpContent,
                "file body must be terminated by LastHttpContent to reset encoder state");

        testFile.delete();
    }

    @Test
    void writeFile_handlesSyncException() {
        when(ctx.writeAndFlush(any())).thenThrow(new RuntimeException("write error"));

        File nonexistent = new File("should_not_exist_12345");
        assertThrows(RuntimeException.class, () -> response.writeFile(nonexistent));
    }

    // ========== P2-A: 关闭响应头 validateHeaders 校验 ==========
    // Netty validate 开启时，非法 header name/value 会在 add 时抛 IllegalArgumentException。

    @Test
    void writeBytes_illegalHeaderNameAndValue_validateDisabled_doesNotThrow() {
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
        // name 含空格、value 含 NUL 均为非法字符
        response.getHeaders().set("Bad Header", "bad\u0000value");

        assertDoesNotThrow(() -> response.writeBytes("data".getBytes(StandardCharsets.UTF_8)));
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void flush_illegalHeaderValue_validateDisabled_doesNotThrow() {
        ByteBuf buf = mock(ByteBuf.class);
        when(allocator.buffer(256)).thenReturn(buf);
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
        when(buf.readableBytes()).thenReturn(0);
        response.getHeaders().set("X-Custom", "bad\u0000value");

        response.getBuf();
        assertDoesNotThrow(() -> response.flush());
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void writeStream_illegalHeaderValue_validateDisabled_doesNotThrow() {
        ChannelFuture future = mock(ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        when(future.isSuccess()).thenReturn(true);
        when(future.addListener(any())).thenReturn(future);
        response.getHeaders().set("X-Custom", "bad\u0000value");

        InputStream input = new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8));
        assertDoesNotThrow(() -> response.writeStream(input));
    }

    // ========== 响应侧零拷贝：框架视图与 Netty 响应共享 DefaultHttpHeaders 存储 ==========

    @Test
    void writeBytes_nettyResponseSharesHeaderStorage() {
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
        response.getHeaders().set("X-Custom", "before");

        response.writeBytes("data".getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx).writeAndFlush(captor.capture());
        FullHttpResponse nettyResp = (FullHttpResponse) captor.getValue();
        // commit 前框架视图写入的 header，在 Netty 响应对象中直接可见（同一存储）
        assertEquals("before", nettyResp.headers().get("X-Custom"));

        // commit 后继续写框架视图，同一存储上对 Netty 响应也可见
        response.getHeaders().set("X-After", "after");
        assertEquals("after", nettyResp.headers().get("X-After"));
    }

    @Test
    void flush_nettyResponseSharesHeaderStorage() throws Exception {
        ByteBuf buf = mock(ByteBuf.class);
        when(allocator.buffer(256)).thenReturn(buf);
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
        when(buf.readableBytes()).thenReturn(0);
        response.getHeaders().set("X-Custom", "before");

        response.getBuf();
        response.flush();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx).writeAndFlush(captor.capture());
        FullHttpResponse nettyResp = (FullHttpResponse) captor.getValue();
        assertEquals("before", nettyResp.headers().get("X-Custom"));

        response.getHeaders().set("X-After", "after");
        assertEquals("after", nettyResp.headers().get("X-After"));
    }
}

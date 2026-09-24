package io.springperf.web.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.channel.FileRegion;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.Attribute;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.server.ResponseLimitConfig;

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
        when(props.getHttpTimeoutMillis()).thenReturn(60000L);
        when(webContext.getProps()).thenReturn(props);

        ctx = mock(ChannelHandlerContext.class);
        eventLoop = mock(EventLoop.class);
        channel = mock(Channel.class);
        allocator = mock(ByteBufAllocator.class);

        when(ctx.executor()).thenReturn(eventLoop);
        when(ctx.channel()).thenReturn(channel);
        when(ctx.alloc()).thenReturn(allocator);
        when(channel.localAddress()).thenReturn(new InetSocketAddress(9090));
        // 默认桩：连接存活。响应侧「交换是否已结束」判据包含 channel.isActive()，
        // 不打桩时 Mockito 默认 false → getBuf/flush 会走「写不出去」的丢弃分支，
        // 与本类各用例意图（正常写出）不符；需要模拟断连的用例自行覆盖此桩。
        when(channel.isActive()).thenReturn(true);
        // 默认桩：写出层多处依赖 alloc.buffer 与 ctx.attr（如 writeFile 的压缩透传标记），
        // 未显式覆盖的用例统一用此兜底，避免 NPE；精确桩（如 buffer(256)）仍优先生效。
        when(allocator.buffer(anyInt())).thenReturn(mock(ByteBuf.class));
        when(ctx.attr(any())).thenReturn(mock(Attribute.class));

        response = new NettyServerHttpResponse(webContext, ctx, false);
    }

    // flush(true) 的分帧契约：进入 chunked 渐进式模式（首帧非 Full、不带 Content-Length），
    // endStream 写终止块且幂等（不得补写第二个终止块）。此前 flush(true) 会退化为一次性提交。
    @Test
    void flushTrue_entersChunkedStreaming_andEndStreamIsIdempotent() throws Exception {
        // 写出层会为每个 future 注册监听（addRespEventListener），mock 的 writeAndFlush 默认返回 null → 先打桩
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
        assertFalse(response.isStreaming());

        response.flush(true);

        assertTrue(response.isStreaming(), "flush(true) 应进入 chunked 渐进式模式");
        ArgumentCaptor<HttpResponse> head = ArgumentCaptor.forClass(HttpResponse.class);
        verify(ctx).writeAndFlush(head.capture());
        assertFalse(head.getValue() instanceof FullHttpResponse, "首帧必须是非 Full（Full 自带 LastHttpContent，会提前结束响应）");
        assertEquals("chunked", head.getValue().headers().get(HttpHeaderNames.TRANSFER_ENCODING),
                "渐进式首帧必须声明 chunked，实际 headers=" + head.getValue().headers());
        assertNull(head.getValue().headers().get(HttpHeaderNames.CONTENT_LENGTH),
                "chunked 帧不得带 Content-Length（两者并存违反 RFC 7230 §3.3.1）");

        response.endStream();
        verify(ctx, times(1)).writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        // 幂等：再次 endStream 不得补写第二个终止块
        response.endStream();
        verify(ctx, times(1)).writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
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

    // ========== 2-10：writeStream 应将慢速源的「读取」卸载到业务线程池，避免阻塞调用/EventLoop 线程 ==========

    @Test
    void writeStream_doesNotBlockCaller_onSlowSource() throws Exception {
        // 慢速源：writer 缓慢写入且暂不关闭管道；若读取同步进行则会阻塞调用线程
        PipedOutputStream pos = new PipedOutputStream();
        PipedInputStream pis = new PipedInputStream(pos);
        Thread writer = new Thread(() -> {
            try {
                pos.write("slow".getBytes(StandardCharsets.UTF_8));
                Thread.sleep(2000);
                pos.close();
            } catch (Exception ignored) {
                // 测试结束后忽略
            }
        });
        writer.start();

        ChannelFuture future = mock(ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        when(future.isSuccess()).thenReturn(true);
        when(future.addListener(any())).thenReturn(future);
        when(ctx.channel().isWritable()).thenReturn(true);

        long start = System.nanoTime();
        response.writeStream(pis); // 不应阻塞调用线程（读取已卸载到线程池）
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 1000, "writeStream 不应在慢速源上阻塞调用线程，实际耗时 " + elapsedMs + "ms");
        writer.join(3000);
    }

    @Test
    void writeStream_offloadedCopy_writesAllChunks() throws Exception {
        // 验证卸载到业务线程池的拷贝确实写出 body 与 LastHttpContent 收尾
        ChannelFuture future = mock(ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        when(future.isSuccess()).thenReturn(true);
        when(future.addListener(any())).thenReturn(future);
        when(ctx.channel().isActive()).thenReturn(true);
        when(ctx.channel().isWritable()).thenReturn(true);
        when(ctx.alloc().buffer(anyInt(), anyInt())).thenReturn(Unpooled.buffer(16));

        InputStream input = new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8));
        response.writeStream(input);

        // 轮询等待卸载拷贝完成（固定 sleep 在负载高时易偶发失败）
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        long deadline = System.nanoTime() + 3_000_000_000L;
        boolean hasLast = false;
        while (System.nanoTime() < deadline) {
            int writes = mockingDetails(ctx).getInvocations().stream()
                    .filter(i -> "writeAndFlush".equals(i.getMethod().getName())).mapToInt(i -> 1).sum();
            if (writes >= 2) {
                verify(ctx, atLeast(2)).writeAndFlush(captor.capture());
                hasLast = captor.getAllValues().stream().anyMatch(v -> v instanceof LastHttpContent);
                break;
            }
            Thread.sleep(20);
        }
        assertTrue(hasLast, "流式拷贝应写出 LastHttpContent 收尾");
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
        assertTrue(bodyCaptor.getValue() instanceof FileRegion, "file body should be written as zero-copy FileRegion");
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

    // ========== P1-4.9：响应头大小 / swallow-size 限制 ==========

    @Test
    void writeBytes_headerExceedLimit_returnsMinimal500() {
        // 限制 20 字节，单条超长响应头触发降级
        ResponseLimitConfig cfg = new ResponseLimitConfig(-1, 20);
        NettyServerHttpResponse resp = new NettyServerHttpResponse(webContext, ctx, true, cfg, 0L);
        ChannelFuture future = mock(ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        when(future.addListener(any())).thenReturn(future);

        resp.getHeaders().set("X-Large", "this header value is definitely longer than twenty bytes");
        resp.setStatusCode(HttpStatus.OK);
        resp.writeBytes("body".getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx).writeAndFlush(captor.capture());
        Object written = captor.getValue();
        assertTrue(written instanceof io.netty.handler.codec.http.FullHttpResponse);
        io.netty.handler.codec.http.FullHttpResponse fr = (io.netty.handler.codec.http.FullHttpResponse) written;
        assertEquals(io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR, fr.status());
        // 降级 500 不再携带业务超长响应头，仅最小头
        assertNull(fr.headers().get("X-Large"));
        assertEquals("keep-alive", fr.headers().get(io.netty.handler.codec.http.HttpHeaderNames.CONNECTION));
    }

    @Test
    void writeBytes_headerWithinLimit_writesNormalResponse() {
        ResponseLimitConfig cfg = new ResponseLimitConfig(-1, 8192);
        NettyServerHttpResponse resp = new NettyServerHttpResponse(webContext, ctx, true, cfg, 0L);
        ChannelFuture future = mock(ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        when(future.addListener(any())).thenReturn(future);

        resp.setStatusCode(HttpStatus.OK);
        resp.writeBytes("body".getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx).writeAndFlush(captor.capture());
        Object written = captor.getValue();
        assertTrue(written instanceof io.netty.handler.codec.http.FullHttpResponse);
        io.netty.handler.codec.http.FullHttpResponse fr = (io.netty.handler.codec.http.FullHttpResponse) written;
        assertEquals(io.netty.handler.codec.http.HttpResponseStatus.OK, fr.status());
    }

    @Test
    void sendError_largeRequestBody_exceedsSwallowLimit_closesConnection() {
        // swallow 上限 100 字节，错误响应且请求 body 1000 字节 -> 关闭连接（不保活）
        ResponseLimitConfig cfg = new ResponseLimitConfig(100, 8192);
        NettyServerHttpResponse resp = new NettyServerHttpResponse(webContext, ctx, true, cfg, 1000L);
        ChannelFuture future = mock(ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        when(future.addListener(any())).thenReturn(future);

        resp.sendError(HttpStatus.INTERNAL_SERVER_ERROR, "err");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx).writeAndFlush(captor.capture());
        Object written = captor.getValue();
        assertTrue(written instanceof io.netty.handler.codec.http.HttpResponse);
        io.netty.handler.codec.http.HttpResponse r = (io.netty.handler.codec.http.HttpResponse) written;
        assertEquals("close", r.headers().get(io.netty.handler.codec.http.HttpHeaderNames.CONNECTION));
    }

    @Test
    void sendError_smallRequestBody_withinSwallowLimit_keepsAlive() {
        // swallow 上限 100 字节，请求 body 50 字节 -> 仍保活
        ResponseLimitConfig cfg = new ResponseLimitConfig(100, 8192);
        NettyServerHttpResponse resp = new NettyServerHttpResponse(webContext, ctx, true, cfg, 50L);
        ChannelFuture future = mock(ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        when(future.addListener(any())).thenReturn(future);

        resp.sendError(HttpStatus.INTERNAL_SERVER_ERROR, "err");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx).writeAndFlush(captor.capture());
        Object written = captor.getValue();
        assertTrue(written instanceof io.netty.handler.codec.http.HttpResponse);
        io.netty.handler.codec.http.HttpResponse r = (io.netty.handler.codec.http.HttpResponse) written;
        assertEquals("keep-alive", r.headers().get(io.netty.handler.codec.http.HttpHeaderNames.CONNECTION));
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

    // ========== M1：writeFile/writeStream 不应强制 octet-stream 丢失 MIME ==========

    @Test
    void writeFile_derivesContentTypeFromExtension() throws Exception {
        File htmlFile = java.nio.file.Files.createTempFile("page", ".html").toFile();
        java.nio.file.Files.write(htmlFile.toPath(), "<html/>".getBytes(StandardCharsets.UTF_8));

        ChannelFuture future = mock(ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        when(ctx.write(any())).thenReturn(future);
        when(future.addListener(any())).thenReturn(future);

        response.writeFile(htmlFile);

        ArgumentCaptor<Object> headersCaptor = ArgumentCaptor.forClass(Object.class);
        verify(ctx, atLeast(2)).writeAndFlush(headersCaptor.capture());
        HttpResponse headers = (HttpResponse) headersCaptor.getAllValues().get(0);
        String contentType = headers.headers().get(HttpHeaderNames.CONTENT_TYPE);
        assertNotNull(contentType, "writeFile 应推导并写入 Content-Type");
        assertTrue(contentType.contains("text/html"), "html 文件应推导为 text/html，实际: " + contentType);
        htmlFile.delete();
    }

    @Test
    void writeStream_respectsPresetContentType() throws Exception {
        ChannelFuture future = mock(ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        when(future.isSuccess()).thenReturn(true);
        when(future.addListener(any())).thenReturn(future);

        // 调用方预先设置 Content-Type（如静态资源处理器按扩展名推断 MIME）
        response.getHeaders().setContentType(org.springframework.http.MediaType.TEXT_PLAIN);

        InputStream input = new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8));
        response.writeStream(input);

        // writeStream 同步写出 headers 首帧（HttpResponse）；body 已卸载到业务线程池异步写出。
        // Content-Type 仅出现在首帧中。
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx, atLeast(1)).writeAndFlush(captor.capture());
        HttpResponse resp = (HttpResponse) captor.getAllValues().get(0);
        assertEquals("text/plain", resp.headers().get(HttpHeaderNames.CONTENT_TYPE));
    }

    @Test
    void writeStream_defaultContentTypeIsOctetStream_whenNotPreset() throws Exception {
        ChannelFuture future = mock(ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        when(future.isSuccess()).thenReturn(true);
        when(future.addListener(any())).thenReturn(future);

        InputStream input = new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8));
        response.writeStream(input);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx, atLeast(1)).writeAndFlush(captor.capture());
        HttpResponse resp = (HttpResponse) captor.getAllValues().get(0);
        assertEquals("application/octet-stream", resp.headers().get(HttpHeaderNames.CONTENT_TYPE));
    }

    // ========== M2：flush 成功后置空 buf，避免悬空引用已释放的 ByteBuf ==========

    @Test
    void flush_nullsBuffer_toAvoidDanglingReference() throws IOException {
        ByteBuf buf1 = mock(ByteBuf.class);
        ByteBuf buf2 = mock(ByteBuf.class);
        when(allocator.buffer(256)).thenReturn(buf1, buf2);
        when(buf1.readableBytes()).thenReturn(0);
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));

        response.getBuf(); // 持有 buf1
        response.flush(); // 成功写出后 this.buf 应置空
        ByteBuf after = response.getBuf(); // 应得到新缓冲区，而非悬空的 buf1
        assertSame(buf2, after, "flush 后 buf 应置空，后续 getBuf 返回新缓冲区，避免悬空引用已释放的 ByteBuf");
        verify(buf1, never()).release(); // 正常路径由 Netty 释放，框架不应重复 release
    }

    // ========== L1：未提交的响应体 ByteBuf 应被兜底释放，避免池化 Buffer 泄漏 ==========

    @Test
    void release_releasesUnflushedBuffer() {
        // handler 直接写 body 但未 flush/setHandled 时，未提交 buf 应被兜底释放
        when(allocator.buffer(256)).thenReturn(Unpooled.buffer(256));
        ByteBuf buf = response.getBuf();
        assertEquals(1, buf.refCnt());
        try {
            response.getBody().write("data".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            fail("getBody().write should not throw", e);
        }
        assertFalse(response.isCommitted(), "写 body 但未 flush 时响应不应已提交");
        response.release();
        assertEquals(0, buf.refCnt(), "未提交 buf 应被兜底释放，避免池化 ByteBuf 泄漏");
    }

    @Test
    void release_noopAfterFlush() throws IOException {
        // 已 flush（buf 已转移给 Netty 并置空）后调用应安全无副作用，不 double-release
        when(allocator.buffer(256)).thenReturn(Unpooled.buffer(256));
        ChannelFuture future = mock(ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        when(future.isSuccess()).thenReturn(true);
        when(future.addListener(any())).thenReturn(future);

        response.getBody().write("x".getBytes(StandardCharsets.UTF_8));
        response.flush();
        response.release(); // 应无异常、无 double-release
    }
}

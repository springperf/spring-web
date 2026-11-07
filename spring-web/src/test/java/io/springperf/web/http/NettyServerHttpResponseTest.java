package io.springperf.web.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
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

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx, atLeastOnce()).writeAndFlush(captor.capture());
        HttpResponse headers = captor.getAllValues().stream().filter(HttpResponse.class::isInstance)
                .map(HttpResponse.class::cast).findFirst().orElseThrow();
        assertEquals("chunked", headers.headers().get(HttpHeaderNames.TRANSFER_ENCODING));
        assertNull(headers.headers().get(HttpHeaderNames.CONTENT_LENGTH),
                "chunked 模式不得同时声明 Content-Length");
    }

    // ========== 2-10：writeStream 应将慢速源的「读取」卸载到业务线程池，避免阻塞调用/EventLoop 线程 ==========

    @Test
    void writeStream_doesNotBlockCaller_onSlowSource() throws Exception {
        // 源在第一次 read 处阻塞直到测试放行：若读取是同步的，writeStream 会卡在这里
        java.util.concurrent.CountDownLatch readingStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch allowRead = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Thread> readThread = new java.util.concurrent.atomic.AtomicReference<>();
        InputStream slow = new InputStream() {
            private boolean firstRead = true;

            @Override
            public int read() {
                throw new UnsupportedOperationException("streamCopy 只使用 read(byte[]) 批量读");
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (!firstRead) {
                    return -1;
                }
                firstRead = false;
                readThread.set(Thread.currentThread());
                readingStarted.countDown();
                try {
                    if (!allowRead.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("读取未被测试放行");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                b[off] = 'x';
                return 1;
            }
        };

        ChannelFuture future = mock(ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        when(future.isSuccess()).thenReturn(true);
        when(future.addListener(any())).thenReturn(future);
        when(ctx.channel().isActive()).thenReturn(true);
        when(ctx.channel().isWritable()).thenReturn(true);
        when(ctx.alloc().buffer(anyInt(), anyInt())).thenReturn(Unpooled.buffer(8));

        try {
            // 关键证据：源仍阻塞在 read 上，writeStream 却已返回 → 读取确实被卸载
            response.writeStream(slow);
            assertTrue(readingStarted.await(5, TimeUnit.SECONDS), "读取应已交棒到后台线程");
            assertNotSame(Thread.currentThread(), readThread.get(), "InputStream.read 必须在业务线程池执行");
        } finally {
            allowRead.countDown();
        }
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

        // 头 + 终结帧都要写出，且文件体长度必须与真实字节数一致（零拷贝 FileRegion）
        ArgumentCaptor<Object> flushed = ArgumentCaptor.forClass(Object.class);
        verify(ctx, times(2)).writeAndFlush(flushed.capture());
        assertEquals("file content".getBytes(StandardCharsets.UTF_8).length,
                ((HttpResponse) flushed.getAllValues().get(0)).headers().getInt(HttpHeaderNames.CONTENT_LENGTH),
                "Content-Length 必须等于文件字节数");
        assertTrue(flushed.getAllValues().get(1) instanceof LastHttpContent, "必须补发 LastHttpContent 收尾");
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(ctx).write(body.capture());
        FileRegion region = assertInstanceOf(FileRegion.class, body.getValue(), "文件体应以零拷贝 FileRegion 写出");
        assertEquals("file content".getBytes(StandardCharsets.UTF_8).length, region.count(),
                "FileRegion 长度必须等于文件字节数");

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

        // 未设置业务 listener 时必须挂载默认失败监听器，且处理失败不能抛
        verify(future).addListener(NettyServerHttpResponse.LOG_ERROR_ON_FAILURE);
        assertDoesNotThrow(() -> NettyServerHttpResponse.LOG_ERROR_ON_FAILURE.operationComplete(future));
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
    void setWritableCallback_offloadsToEventLoop() {
        Runnable callback = mock(Runnable.class);
        response.setWritableCallback(callback);

        // 当前线程不是 EventLoop：装配任务必须投递到 EventLoop 执行
        verify(eventLoop).execute(any(Runnable.class));
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
    void writeFile_headerWriteThrows_propagatesOriginalCause() {
        when(ctx.writeAndFlush(any())).thenThrow(new RuntimeException("write error"));

        // 失败点在「写响应头」而非文件读取：文件是否存在与本次断言无关
        File nonexistent = new File("should_not_exist_12345");
        RuntimeException ex = assertThrows(RuntimeException.class, () -> response.writeFile(nonexistent));
        assertEquals("write error", ex.getCause().getMessage(),
                "应向上传播写头失败本身，而不是 FileNotFound 等其他异常");
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

    /**
     * flush 路径（非 writeBytes）：响应头超限降级为最小 500 时，本次 flush 已经 takePendingBuf() 摘出来的业务 body 必须被就地释放——writeHeaderTooLarge 只释放
     * this.buf（字段已摘空 → null）， 若本分支不释放，这块池化 buf 从此不可达（paranoid 实测泄漏路径，见
     * ResponseHeaderLimitE2eTest#oversizedResponseHeaders_degradeToMinimal500）。
     */
    @Test
    void flush_responseHeadersExceedLimit_releasesPendingBuf_andWritesMinimal500() {
        ChannelFuture future = mock(ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        when(future.addListener(any())).thenReturn(future);
        ByteBuf body = Unpooled.buffer(64);
        when(allocator.buffer(256)).thenReturn(body);

        NettyServerHttpResponse resp = new NettyServerHttpResponse(webContext, ctx, true,
                new ResponseLimitConfig(-1, 512), 0L);
        char[] bigHeader = new char[2000];
        java.util.Arrays.fill(bigHeader, 'b');
        resp.getHeaders().set("X-Big", new String(bigHeader));

        assertDoesNotThrow(() -> resp.getBody().write("should-not-be-written".getBytes(StandardCharsets.UTF_8)));
        assertDoesNotThrow(() -> resp.flush());

        assertEquals(0, body.refCnt(), "响应头超限降级为最小 500 时，已缓冲的 body ByteBuf 必须被释放，否则池化泄漏");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx).writeAndFlush(captor.capture());
        io.netty.handler.codec.http.FullHttpResponse fr = (io.netty.handler.codec.http.FullHttpResponse) captor
                .getValue();
        assertEquals(io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR, fr.status());
        assertEquals(0, fr.content().readableBytes(), "降级响应体必须为空（业务内容已丢弃）");
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
        // 校验关闭不是「静默放弃」：响应仍须真正写出
        verify(ctx).writeAndFlush(any());
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
    void writeStream_headRequest_closesInputStream() {
        ChannelFuture future = mock(ChannelFuture.class);
        when(ctx.writeAndFlush(any())).thenReturn(future);
        when(future.isSuccess()).thenReturn(true);
        when(future.addListener(any())).thenReturn(future);

        // HEAD：只写 headers + LastHttpContent 收尾，不写 body，且必须关闭 input。
        // 修复前该分支提前 return，streamCopy 的 finally 与 catch 里的关闭都到不了，
        // 非文件 Resource 的 HEAD 请求每请求泄漏一个 InputStream / 文件句柄（P0）。
        CloseTrackingInputStream input = new CloseTrackingInputStream(
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        response.markAsHeadRequest();
        response.writeStream(input);

        assertTrue(input.closed, "HEAD 分支必须关闭 input（P0 回归）");
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx, times(2)).writeAndFlush(captor.capture());
        assertTrue(captor.getAllValues().get(0) instanceof HttpResponse, "首帧应是响应头");
        assertTrue(captor.getAllValues().get(1) instanceof LastHttpContent, "次帧应是收尾块");
    }

    @Test
    void writeStream_afterCommitted_closesInputStream() {
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
        // 先提交响应，第二次 writeStream 会在 !setCommitted() 处早退；
        // 早退分支同样由 writeStream 负责关流，否则每请求泄漏一个文件句柄。
        response.writeBytes("x".getBytes(StandardCharsets.UTF_8));
        CloseTrackingInputStream input = new CloseTrackingInputStream(
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));

        response.writeStream(input);

        assertTrue(input.closed, "已提交响应的早退分支必须关闭 input");
    }

    @Test
    void writeStream_afterCommitted_nullInput_doesNotThrow() {
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
        response.writeBytes("x".getBytes(StandardCharsets.UTF_8));
        // 空值边界：早退分支的关流动作不得因 input 为 null 引入 NPE
        assertDoesNotThrow(() -> response.writeStream((InputStream) null));
        // 已提交后必须早退：不得再写出第二帧
        verify(ctx, times(1)).writeAndFlush(any());
    }

    private static final class CloseTrackingInputStream extends FilterInputStream {

        private boolean closed;

        private CloseTrackingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
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

        ByteBuf data = response.getBuf();
        response.getBody().write("x".getBytes(StandardCharsets.UTF_8));
        response.flush();
        response.release();

        assertEquals(1, data.refCnt(), "flush 后 buf 所有权已转移，release() 不得再次释放");
    }
}

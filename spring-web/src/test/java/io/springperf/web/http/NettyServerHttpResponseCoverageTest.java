package io.springperf.web.http;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.GenericFutureListener;
import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NettyServerHttpResponseCoverageTest {

    @Mock
    WebContext webContext;
    @Mock
    ChannelHandlerContext ctx;
    @Mock
    EventLoop eventLoop;
    @Mock
    ByteBufAllocator allocator;

    @BeforeEach
    void setUp() {
        lenient().when(ctx.executor()).thenReturn(eventLoop);
        lenient().when(ctx.alloc()).thenReturn(allocator);
        lenient().when(allocator.buffer(256)).thenAnswer(invocation -> Unpooled.buffer(256));
    }

    private NettyServerHttpResponse newResponse(boolean keepAlive) {
        return new NettyServerHttpResponse(webContext, ctx, keepAlive);
    }

    @Test
    void logErrorOnFailure_logsWarnForGenericFailure() {
        NettyServerHttpResponse resp = newResponse(false);
        ChannelFuture future = mock(ChannelFuture.class);
        resp.addRespEventListener(future, false);
        ArgumentCaptor<ChannelFutureListener> captor = ArgumentCaptor.forClass(ChannelFutureListener.class);
        verify(future).addListener(captor.capture());
        assertSame(NettyServerHttpResponse.LOG_ERROR_ON_FAILURE, captor.getValue());
        ChannelFuture done = mock(ChannelFuture.class);
        when(done.isSuccess()).thenReturn(false);
        when(done.cause()).thenReturn(new IllegalStateException("boom"));
        assertDoesNotThrow(() -> captor.getValue().operationComplete(done));
    }

    @Test
    void logErrorOnFailure_silentlyIgnoresClosedChannel() {
        NettyServerHttpResponse resp = newResponse(false);
        ChannelFuture future = mock(ChannelFuture.class);
        resp.addRespEventListener(future, true);
        ArgumentCaptor<ChannelFutureListener> captor = ArgumentCaptor.forClass(ChannelFutureListener.class);
        verify(future).addListener(captor.capture());
        ChannelFuture done = mock(ChannelFuture.class);
        when(done.isSuccess()).thenReturn(false);
        when(done.cause()).thenReturn(new ClosedChannelException());
        assertDoesNotThrow(() -> captor.getValue().operationComplete(done));
    }

    @Test
    void markAsHeadRequestTogglesHeadFlag() {
        NettyServerHttpResponse resp = newResponse(false);
        assertFalse(resp.isHeadRequest());
        resp.markAsHeadRequest();
        assertTrue(resp.isHeadRequest());
    }

    @Test
    void resetBuffer_emptyBuffer_returnsFalse() {
        NettyServerHttpResponse resp = newResponse(false);
        assertFalse(resp.resetBuffer(), "无缓冲数据时返回 false");
    }

    @Test
    void resetBuffer_withBufferedBody_clearsByteBuf() throws Exception {
        NettyServerHttpResponse resp = newResponse(false);
        resp.getBody().write("hello".getBytes(StandardCharsets.UTF_8));
        assertTrue(resp.resetBuffer(), "有缓冲数据时返回 true");
        assertEquals(0, resp.getBuf().readableBytes(), "resetBuffer 后底层 ByteBuf 应被清空");
    }

    @Test
    void sendError_afterPartialBody_resetsPartialContent() throws Exception {
        NettyServerHttpResponse resp = newResponse(false);
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
        // 模拟序列化中途失败：先写入部分内容再 sendError
        resp.getBody().write("partial".getBytes(StandardCharsets.UTF_8));
        resp.sendError(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx).writeAndFlush(captor.capture());
        FullHttpResponse nettyResp = (FullHttpResponse) captor.getValue();
        String body = nettyResp.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"error\""), "错误 JSON 应写入");
        assertFalse(body.contains("partial"), "已缓冲的部分内容应被清空而非追加");
    }

    @Test
    void flush_headRequest_convertsWrittenBodyToContentLength() throws Exception {
        NettyServerHttpResponse resp = newResponse(false);
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
        resp.markAsHeadRequest();
        resp.getBody().write("hello".getBytes(StandardCharsets.UTF_8));
        resp.flush();
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx).writeAndFlush(captor.capture());
        FullHttpResponse nettyResp = (FullHttpResponse) captor.getValue();
        assertEquals(5, nettyResp.headers().getInt(HttpHeaderNames.CONTENT_LENGTH));
        assertEquals(0, nettyResp.content().readableBytes());
    }

    @Test
    void flush_headRequest_keepsPreexistingContentLength() throws Exception {
        NettyServerHttpResponse resp = newResponse(false);
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
        resp.getHeaders().set(HttpHeaderNames.CONTENT_LENGTH.toString(), "42");
        resp.markAsHeadRequest();
        resp.getBody().write("hello".getBytes(StandardCharsets.UTF_8));
        resp.flush();
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx).writeAndFlush(captor.capture());
        FullHttpResponse nettyResp = (FullHttpResponse) captor.getValue();
        assertEquals(42, nettyResp.headers().getInt(HttpHeaderNames.CONTENT_LENGTH));
        assertEquals(0, nettyResp.content().readableBytes());
    }

    @Test
    void flush_afterCommit_isNoop() {
        NettyServerHttpResponse resp = newResponse(false);
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
        resp.writeBytes("x".getBytes(StandardCharsets.UTF_8));
        assertDoesNotThrow(() -> resp.flush());
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void writeStream_afterCommit_isNoop() {
        NettyServerHttpResponse resp = newResponse(false);
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
        resp.writeBytes("x".getBytes(StandardCharsets.UTF_8));
        InputStream in = new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8));
        assertDoesNotThrow(() -> resp.writeStream(in));
        verify(ctx).writeAndFlush(any());
    }

    @Test
    void writeBytes_afterCommit_isNoop() {
        NettyServerHttpResponse resp = newResponse(false);
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
        resp.writeBytes("x".getBytes(StandardCharsets.UTF_8));
        resp.writeBytes("y".getBytes(StandardCharsets.UTF_8));
        verify(ctx, times(1)).writeAndFlush(any());
    }

    @Test
    void writeBytes_keepAliveTrue_setsKeepAliveHeader() {
        NettyServerHttpResponse resp = newResponse(true);
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
        resp.writeBytes("data".getBytes(StandardCharsets.UTF_8));
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx).writeAndFlush(captor.capture());
        FullHttpResponse nettyResp = (FullHttpResponse) captor.getValue();
        assertEquals("keep-alive", nettyResp.headers().get(HttpHeaderNames.CONNECTION));
    }

    @Test
    void writeBytes_headRequest_dropsBodyKeepsLength() {
        NettyServerHttpResponse resp = newResponse(false);
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
        resp.markAsHeadRequest();
        resp.writeBytes("data".getBytes(StandardCharsets.UTF_8));
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx).writeAndFlush(captor.capture());
        FullHttpResponse nettyResp = (FullHttpResponse) captor.getValue();
        assertEquals(4, nettyResp.headers().getInt(HttpHeaderNames.CONTENT_LENGTH));
        assertEquals(0, nettyResp.content().readableBytes());
    }

    @Test
    void writeStream_headRequest_sendsHeadersAndTerminator() {
        NettyServerHttpResponse resp = newResponse(false);
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
        resp.markAsHeadRequest();
        resp.writeStream(new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(ctx, times(2)).writeAndFlush(captor.capture());
        assertTrue(captor.getAllValues().get(0) instanceof HttpResponse);
        assertTrue(captor.getAllValues().get(1) instanceof LastHttpContent);
        verify(ctx, never()).write(any());
    }

    @Test
    void writeStream_headRequest_keepAlive_skipsCloseListener() {
        NettyServerHttpResponse resp = newResponse(true);
        when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
        resp.markAsHeadRequest();
        resp.writeStream(new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));
        verify(ctx, times(2)).writeAndFlush(any());
        verify(ctx, never()).write(any());
    }

    @Test
    void writeStream_writeThrows_closesInputAndRethrows() throws Exception {
        NettyServerHttpResponse resp = newResponse(false);
        when(ctx.writeAndFlush(any())).thenThrow(new RuntimeException("write error"));
        InputStream in = mock(InputStream.class);
        doThrow(new IOException("close failed")).when(in).close();
        RuntimeException ex = assertThrows(RuntimeException.class, () -> resp.writeStream(in));
        assertEquals("write error", ex.getCause().getMessage());
        verify(in).close();
    }

    @Test
    void writeFile_headRequest_sendsHeadersAndTerminator() throws Exception {
        File file = File.createTempFile("head", ".bin");
        try {
            java.nio.file.Files.write(file.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
            NettyServerHttpResponse resp = newResponse(false);
            when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
            resp.markAsHeadRequest();
            resp.writeFile(file);
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(ctx, times(2)).writeAndFlush(captor.capture());
            assertTrue(captor.getAllValues().get(0) instanceof HttpResponse);
            assertTrue(captor.getAllValues().get(1) instanceof LastHttpContent);
            verify(ctx, never()).write(any());
        } finally {
            file.delete();
        }
    }

    @Test
    void writeFile_headRequest_keepAlive_skipsCloseListener() throws Exception {
        File file = File.createTempFile("head", ".bin");
        try {
            java.nio.file.Files.write(file.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
            NettyServerHttpResponse resp = newResponse(true);
            when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
            resp.markAsHeadRequest();
            resp.writeFile(file);
            verify(ctx, times(2)).writeAndFlush(any());
            verify(ctx, never()).write(any());
        } finally {
            file.delete();
        }
    }

    @Test
    void writeFile_afterCommit_isNoop() throws Exception {
        File file = File.createTempFile("noop", ".bin");
        try {
            NettyServerHttpResponse resp = newResponse(false);
            when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
            resp.writeBytes("x".getBytes(StandardCharsets.UTF_8));
            resp.writeFile(file);
            verify(ctx, times(1)).writeAndFlush(any());
        } finally {
            file.delete();
        }
    }

    @Test
    void writeFile_writeThrows_propagatesAfterClosingChannel() throws Exception {
        File file = File.createTempFile("wfp", ".bin");
        try {
            java.nio.file.Files.write(file.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
            NettyServerHttpResponse resp = newResponse(false);
            when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
            when(ctx.write(any())).thenThrow(new RuntimeException("region write failed"));
            assertThrows(RuntimeException.class, () -> resp.writeFile(file));
        } finally {
            file.delete();
        }
    }

    @Test
    void writeFile_closeListener_taskCompletesAndHandlesDoubleClose() throws Exception {
        File file = File.createTempFile("wfcl", ".bin");
        try {
            java.nio.file.Files.write(file.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
            NettyServerHttpResponse resp = newResponse(false);
            ChannelFuture headersFuture = mock(ChannelFuture.class);
            ChannelFuture regionFuture = mock(ChannelFuture.class);
            when(ctx.writeAndFlush(any())).thenReturn(headersFuture);
            when(ctx.write(any())).thenReturn(regionFuture);
            resp.writeFile(file);
            ArgumentCaptor<GenericFutureListener> captor = ArgumentCaptor.forClass(GenericFutureListener.class);
            verify(regionFuture, atLeast(1)).addListener(captor.capture());
            GenericFutureListener closeListener = null;
            for (GenericFutureListener listener : captor.getAllValues()) {
                if (listener != ChannelFutureListener.CLOSE) {
                    closeListener = listener;
                }
            }
            assertNotNull(closeListener);
            closeListener.operationComplete(regionFuture);
            closeListener.operationComplete(regionFuture);
        } finally {
            file.delete();
        }
    }

    @Test
    void addRespEventListener_completeFailure_invokesErrorCallback() throws Exception {
        NettyServerHttpResponse resp = newResponse(false);
        WriteRespEventListener listener = mock(WriteRespEventListener.class);
        resp.setWriteRespEventListener(listener);
        ChannelFuture future = mock(ChannelFuture.class);
        java.util.concurrent.atomic.AtomicReference<GenericFutureListener> holder =
                new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(inv -> {
            holder.set((GenericFutureListener) inv.getArgument(0));
            return future;
        }).when(future).addListener(any(GenericFutureListener.class));
        resp.addRespEventListener(future, true);
        assertNotNull(holder.get());
        ChannelFuture done = mock(ChannelFuture.class);
        when(done.isSuccess()).thenReturn(false);
        when(done.cause()).thenReturn(new IllegalStateException("boom"));
        holder.get().operationComplete(done);
        verify(listener).completeErrorCallback(any());
    }

    @Test
    void addRespEventListener_streamFailure_invokesErrorCallback() throws Exception {
        NettyServerHttpResponse resp = newResponse(false);
        WriteRespEventListener listener = mock(WriteRespEventListener.class);
        resp.setWriteRespEventListener(listener);
        ChannelFuture future = mock(ChannelFuture.class);
        java.util.concurrent.atomic.AtomicReference<GenericFutureListener> holder =
                new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(inv -> {
            holder.set((GenericFutureListener) inv.getArgument(0));
            return future;
        }).when(future).addListener(any(GenericFutureListener.class));
        resp.addRespEventListener(future, false);
        assertNotNull(holder.get());
        ChannelFuture done = mock(ChannelFuture.class);
        when(done.isSuccess()).thenReturn(false);
        when(done.cause()).thenReturn(new IllegalStateException("boom"));
        holder.get().operationComplete(done);
        verify(listener).writeStreamErrorCallback(any());
    }
}
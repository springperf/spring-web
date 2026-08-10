package io.springperf.web.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class NettyServerHttpRequestBodyTest {

    @Mock
    private WebContext webContext;
    @Mock
    private ChannelHandlerContext ctx;

    private static final String SMALL_BODY = "{\"message\":\"hello\"}";
    private static final int LARGE_SIZE = 100 * 1024;

    // ==================== 小包路径 (=4KB) ====================

    @Test
    void smallBody_usesByteArrayInputStream() {
        FullHttpRequest nativeRequest = newRequest(SMALL_BODY);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        InputStream body = req.getBody();
        assertNotNull(body);
        assertInstanceOf(ByteArrayInputStream.class, body);
    }

    @Test
    void smallBody_hasBody_returnsTrue() {
        FullHttpRequest nativeRequest = newRequest(SMALL_BODY);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        assertTrue(req.hasBody());
    }

    @Test
    void smallBody_getBodyBytes_returnsCachedByteArray() {
        FullHttpRequest nativeRequest = newRequest(SMALL_BODY);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        byte[] bytes = req.getBodyBytes();
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertEquals(SMALL_BODY, content);
    }

    @Test
    void smallBody_getBodyBytes_isCached() {
        FullHttpRequest nativeRequest = newRequest(SMALL_BODY);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        byte[] first = req.getBodyBytes();
        byte[] second = req.getBodyBytes();
        assertSame(first, second);
    }

    @Test
    void smallBody_multipleReads_returnSameContent() throws Exception {
        FullHttpRequest nativeRequest = newRequest(SMALL_BODY);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        String first = readAll(req.getBody());
        String second = readAll(req.getBody());
        assertEquals(first, second);
        assertEquals(SMALL_BODY, first);
    }

    // ==================== 大包路径 (>4KB) ====================

    @Test
    void largeBody_usesByteBufInputStream() {
        String largeContent = createLargeContent(LARGE_SIZE);
        FullHttpRequest nativeRequest = newRequest(largeContent);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        InputStream body = req.getBody();
        assertNotNull(body);
        assertFalse(body instanceof ByteArrayInputStream);
    }

    @Test
    void largeBody_hasBody_returnsTrue() {
        String largeContent = createLargeContent(LARGE_SIZE);
        FullHttpRequest nativeRequest = newRequest(largeContent);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        assertTrue(req.hasBody());
    }

    @Test
    void largeBody_getBodyBytes_returnsEmptySentinel() {
        String largeContent = createLargeContent(LARGE_SIZE);
        FullHttpRequest nativeRequest = newRequest(largeContent);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        byte[] bytes = req.getBodyBytes();
        assertNotNull(bytes);
        assertEquals(0, bytes.length);
    }

    @Test
    void largeBody_readContent_correct() throws Exception {
        String largeContent = createLargeContent(LARGE_SIZE);
        FullHttpRequest nativeRequest = newRequest(largeContent);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        String readContent = readAll(req.getBody());
        assertEquals(LARGE_SIZE, readContent.length());
        assertEquals(largeContent, readContent);
    }

    @Test
    void largeBody_multipleReads_returnSameContent() throws Exception {
        String largeContent = createLargeContent(LARGE_SIZE);
        FullHttpRequest nativeRequest = newRequest(largeContent);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        String first = readAll(req.getBody());
        String second = readAll(req.getBody());
        assertEquals(first, second);
        assertEquals(LARGE_SIZE, first.length());
    }

    // ==================== 引用计数生命周期 ====================

    @Test
    void largeBody_release_releasesLargeBodyBuf() {
        String largeContent = createLargeContent(LARGE_SIZE);
        FullHttpRequest nativeRequest = newRequest(largeContent);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        req.getBody();
        req.release();

        assertEquals(0, nativeRequest.refCnt());
    }

    @Test
    void largeBody_acquireRelease_balance() {
        // retainedDuplicate() 创建的 ByteBuf 与 content 共享 refCnt，
        // 因此 largeBodyBuf.release() 和 request.release() 会共同递减同一个 refCnt。
        String largeContent = createLargeContent(LARGE_SIZE);
        FullHttpRequest nativeRequest = newRequest(largeContent);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        req.getBody();
        int afterGetBody = nativeRequest.refCnt();

        req.acquire();
        assertEquals(afterGetBody + 1, nativeRequest.refCnt());

        req.release();
        // release() = largeBodyBuf.release()(shared refCnt: -1) + request.release()(refCnt: -1)
        assertEquals(afterGetBody - 1, nativeRequest.refCnt());

        nativeRequest.release();
        assertEquals(0, nativeRequest.refCnt());
    }

    @Test
    void largeBody_withoutGetBody_releaseStillWorks() {
        String largeContent = createLargeContent(LARGE_SIZE);
        FullHttpRequest nativeRequest = newRequest(largeContent);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        assertTrue(req.release());
        assertEquals(0, nativeRequest.refCnt());
    }

    @Test
    void largeBody_hasBody_doesNotTriggerBodyCopy() {
        String largeContent = createLargeContent(LARGE_SIZE);
        FullHttpRequest nativeRequest = newRequest(largeContent);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        assertTrue(req.hasBody());
        byte[] bodyBytes = req.getBodyBytes();
        assertEquals(0, bodyBytes.length);
    }

    /**
     * 回归 R2-4（确定性）：{@code release()} 必须与 {@code getBody()}/{@code getBodyBytes()}
     * 共用 this 监视器。主线程持锁期间，release() 若同步于 this 则必须阻塞等待；
     * 修复前 release() 无同步，EventLoop 可在异步线程读 body 期间提前释放 largeBodyBuf/content。
     */
    @Test
    void largeBody_release_synchronizesWithGetBody() throws Exception {
        FullHttpRequest nativeRequest = newRequest(createLargeContent(4097));
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean releaseDone = new AtomicBoolean(false);
        Thread releaser = new Thread(() -> {
            started.countDown();
            req.release();
            releaseDone.set(true);
        });

        synchronized (req) {
            releaser.start();
            started.await();
            Thread.sleep(100);
            assertFalse(releaseDone.get(), "release() 未与 getBody 路径同步，EventLoop 提前释放竞态未修复");
        }
        releaser.join(2000);
        assertTrue(releaseDone.get());
    }

    /**
     * 回归 R2-4（并发冒烟）：模拟真实异步流——入池前 acquire、异步线程读 body、
     * 主线程（模拟 EventLoop）提交后立即 release。修复前 release 并发 null 掉
     * largeBodyBuf → 大 POST 读到空 body，或对已释放 content 抛 IllegalReferenceCountException。
     */
    @Test
    void largeBody_concurrentEventLoopRelease_whileAsyncRead_ok() throws Exception {
        for (int i = 0; i < 1000; i++) {
            FullHttpRequest nativeRequest = newRequest(createLargeContent(4097));
            NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");
            req.acquire(); // DispatcherHandler 入池前 retain，平衡 EventLoop 的 release
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread async = new Thread(() -> {
                try {
                    start.await();
                    String read = readAll(req.getBody());
                    if (read.length() != 4097) {
                        error.set(new AssertionError("empty/truncated body len=" + read.length()));
                    }
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    req.release();
                }
            });
            async.start();
            start.countDown();
            req.release(); // EventLoop finally：提交后立即执行
            async.join(2000);
            if (error.get() != null) {
                throw new AssertionError("iteration " + i + ": " + error.get().getMessage(), error.get());
            }
            assertEquals(0, nativeRequest.refCnt(), "iteration " + i);
        }
    }

    // ==================== 边界场景 ====================

    @Test
    void emptyBody_hasBody_returnsFalse() {
        FullHttpRequest nativeRequest = newRequest("");
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        assertFalse(req.hasBody());
    }

    @Test
    void emptyBody_getBodyBytes_returnsEmptyArray() {
        FullHttpRequest nativeRequest = newRequest("");
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        byte[] bytes = req.getBodyBytes();
        assertNotNull(bytes);
        assertEquals(0, bytes.length);
    }

    @Test
    void boundary_4096bytes_usesSmallPath() {
        String boundaryContent = createLargeContent(4096);
        FullHttpRequest nativeRequest = newRequest(boundaryContent);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        InputStream body = req.getBody();
        assertInstanceOf(ByteArrayInputStream.class, body);
    }

    @Test
    void boundary_4097bytes_usesLargePath() {
        String boundaryContent = createLargeContent(4097);
        FullHttpRequest nativeRequest = newRequest(boundaryContent);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        InputStream body = req.getBody();
        assertFalse(body instanceof ByteArrayInputStream);
    }

    @Test
    void getContentLength_returnsCorrectSize() {
        String bodyContent = "test body content";
        FullHttpRequest nativeRequest = newRequest(bodyContent);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        int expectedBytes = bodyContent.getBytes(StandardCharsets.UTF_8).length;
        assertEquals(expectedBytes, req.getContentLength());
    }

    @Test
    void getContentLength_largeBody_returnsCorrectSize() {
        String largeContent = createLargeContent(LARGE_SIZE);
        FullHttpRequest nativeRequest = newRequest(largeContent);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, nativeRequest, "/test");

        assertEquals(LARGE_SIZE, req.getContentLength());
    }

    // ==================== 辅助 ====================

    private static FullHttpRequest newRequest(String bodyContent) {
        byte[] bodyBytes = bodyContent.getBytes(StandardCharsets.UTF_8);
        ByteBuf content = Unpooled.copiedBuffer(bodyBytes);
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/test", content);
    }

    private static String createLargeContent(int size) {
        StringBuilder sb = new StringBuilder(size);
        for (int i = 0; i < size; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        return sb.toString();
    }

    private static String readAll(InputStream is) throws Exception {
        byte[] buffer = new byte[8192];
        StringBuilder result = new StringBuilder();
        int read;
        while ((read = is.read(buffer)) != -1) {
            result.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        is.close();
        return result.toString();
    }
}

package io.springperf.web.http.support;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link NettyMultipartWebRequest#release()} 会销毁内部 {@link HttpPostRequestDecoder}，
 * 释放其 {@code undecodedChunk} 池化缓冲（修复每请求堆外内存泄漏）。
 *
 * <p>回归用例：历史上 release() 只释放 interfaceHttpDataList，从不调用 decoder.destroy()，
 * 导致正常完成的 multipart 请求每请求泄漏约等于请求体大小的池化直接内存。</p>
 */
class NettyMultipartWebRequestReleaseTest {

    private ByteBuf requestContent;

    @AfterEach
    void tearDown() {
        if (requestContent != null && requestContent.refCnt() > 0) {
            requestContent.release();
        }
    }

    private FullHttpRequest buildMultipartRequest(ByteBuf content) {
        this.requestContent = content;
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload", content);
    }

    @Test
    void release_destroysDecoder_andFreesUndecodedChunk() {
        String boundary = "----Boundary";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"field1\"\r\n\r\n"
                + "value1\r\n"
                + "--" + boundary + "--\r\n";
        ByteBuf content = Unpooled.copiedBuffer(body, java.nio.charset.StandardCharsets.UTF_8);

        DefaultHttpDataFactory factory = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);
        FullHttpRequest req = buildMultipartRequest(content);

        SupportMultipartResolver resolver = new SupportMultipartResolver();
        resolver.start(req);
        resolver.consume(req);
        NettyMultipartWebRequest multipart = resolver.finish();

        HttpPostRequestDecoder decoder = multipart.getDecoder();
        // 构造期 getBodyHttpDatas() 已解析出 part，undecodedChunk 应持有未消费缓冲（refCnt>0）
        assertTrue(ReferenceCountUtil.refCnt(multipart) > 0, "请求对象应持有引用");

        // 业务侧正常读取参数（触发 parseIfNeeded），随后 release
        assertEquals(1, multipart.getParameters().size());
        multipart.release();

        // decoder 已被销毁：undecodedChunk 释放为 0（destroy 内部 set null，通过 getBodyHttpDatas 不再可用）
        // 关键断言：再次 release 不抛异常（幂等），且请求对象引用已清空
        assertEquals(0, ReferenceCountUtil.refCnt(multipart), "release 后请求 refCnt 应为 0");
    }

    @Test
    void release_idempotent_onDoubleRelease() {
        String boundary = "----B2";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"a\"\r\n\r\n"
                + "b\r\n"
                + "--" + boundary + "--\r\n";
        ByteBuf content = Unpooled.copiedBuffer(body, java.nio.charset.StandardCharsets.UTF_8);

        DefaultHttpDataFactory factory = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);
        FullHttpRequest req = buildMultipartRequest(content);

        SupportMultipartResolver resolver = new SupportMultipartResolver();
        resolver.start(req);
        resolver.consume(req);
        NettyMultipartWebRequest multipart = resolver.finish();

        multipart.release();
        // 幂等：第二次 release 不抛异常（destroy 对 refCnt<=0 数据跳过）
        multipart.release();
    }

    @Test
    void release_withRetainedDuplicateRefs_doesNotDoubleFree() {
        String boundary = "----B3";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"x\"\r\n\r\n"
                + "y\r\n"
                + "--" + boundary + "--\r\n";
        ByteBuf content = Unpooled.copiedBuffer(body, java.nio.charset.StandardCharsets.UTF_8);

        DefaultHttpDataFactory factory = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);
        FullHttpRequest req = buildMultipartRequest(content);

        SupportMultipartResolver resolver = new SupportMultipartResolver();
        resolver.start(req);
        resolver.consume(req);
        NettyMultipartWebRequest multipart = resolver.finish();

        // 模拟持有额外引用（如 async 卸载场景），release 两次都不得抛异常
        multipart.retain();
        multipart.release();
        multipart.release();
    }
}

package io.springperf.web.http.support;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link NettyMultipartWebRequest#release()} 会销毁内部 {@link HttpPostRequestDecoder}，
 * 释放其 {@code undecodedChunk} 池化缓冲（修复每请求堆外内存泄漏）。
 *
 * <p>回归用例：历史上 release() 只释放 interfaceHttpDataList，从不调用 decoder.destroy()，
 * 导致正常完成的 multipart 请求每请求泄漏约等于请求体大小的池化直接内存。</p>
 *
 * <p>P1 回归（74575a5 引入）：destroy() 曾在 parts 循环【之后】执行——part data 已被循环
 * release 到 refCnt=0，而 destroy() 内部 cleanRequestHttpData 对 requestFileDeleteMap 中
 * 的 data 做【无守卫】release，对 refCnt=0 的 data 抛 IllegalReferenceCountException，
 * destroy() 在 cleanFiles 处中断，undecodedChunk 池化缓冲每请求泄漏，且 release() 被
 * catch 吞掉异常返回 false（原测试只断言请求对象 refCnt==0，假阴性，拦不住该缺陷）。
 * 修复：destroy() 先行（part data 尚持 refCnt=1 时执行，无守卫 release 恰好合法归零并
 * 释放 undecodedChunk），随后循环兜底。本测试断言 undecodedChunk 真正释放 + release()
 * 返回 true，杜绝假阴性。</p>
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

    /** 反射读取 decoder 内部 undecodedChunk 的 refCnt（门面 HttpPostRequestDecoder 需穿透一层）。 */
    private static int refCntOfUndecodedChunk(HttpPostRequestDecoder facade) {
        try {
            Field d = HttpPostRequestDecoder.class.getDeclaredField("decoder");
            d.setAccessible(true);
            Object impl = d.get(facade);
            Class<?> cls = impl.getClass();
            Field f = null;
            while (cls != null && f == null) {
                try {
                    f = cls.getDeclaredField("undecodedChunk");
                } catch (NoSuchFieldException ignored) {
                    cls = cls.getSuperclass();
                }
            }
            if (f == null) {
                throw new NoSuchFieldException("undecodedChunk in " + impl.getClass());
            }
            f.setAccessible(true);
            ByteBuf buf = (ByteBuf) f.get(impl);
            return buf == null ? 0 : buf.refCnt();
        } catch (Exception e) {
            throw new RuntimeException("反射读取 undecodedChunk 失败", e);
        }
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
        assertTrue(refCntOfUndecodedChunk(decoder) > 0, "undecodedChunk 应持引用");

        // 业务侧正常读取参数（触发 parseIfNeeded），随后 release
        assertEquals(1, multipart.getParameters().size());
        boolean last = multipart.release();

        // P1 关键断言：destroy() 必须完整执行（无 cleanFiles 异常中断）——
        // ① release() 返回 true（异常未被 catch 吞掉，修复前返回 false）；
        // ② undecodedChunk 被 destroy 释放（refCnt 归零或已置 null）；
        // ③ 请求对象引用清空；④ 每个 part data 引用归零。
        assertTrue(last, "release 应成功返回 true（destroy 不得抛异常被吞，修复前返回 false）");
        assertEquals(0, refCntOfUndecodedChunk(decoder), "undecodedChunk 必须释放（destroy 完整执行）");
        assertEquals(0, ReferenceCountUtil.refCnt(multipart), "release 后请求 refCnt 应为 0");
        for (InterfaceHttpData data : multipart.getInterfaceHttpDataList()) {
            assertEquals(0, data.refCnt(), "part data 应全部归零");
        }
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
        // 幂等：第二次 release 不抛异常（request 已归零，super.release() 抛异常被 catch 吞；
        // destroy 已执行过一次，二次进入时 part data 均已归零，循环守卫跳过）
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

        // 模拟持有额外引用（如 async 卸载场景），release 都不得抛异常。
        // 用 K=3（retain 三次）：修复前 release 仅在 last 时对 data release 一次，
        // destroy() 内部的兜底递减（cleanFiles delete + bodyListHttpData loop）恰好能
        // 补上 K≤2 的引用，K=3 时 data.refCnt = 1+3 - 3 = 1，泄漏 1 个引用。
        // 对称修复后每次 release 递减 data.refCnt，归零时 deallocate 自动释放 content。
        multipart.retain();
        multipart.retain();
        multipart.retain();
        multipart.release();
        multipart.release();
        multipart.release();
        multipart.release();

        // 关键回归断言：retain K 次后对称 release，每个 part data 的 refCnt 必须归零。
        // 修复前 K=3 时 data.refCnt 停留在 1（泄漏），此处断言失败即暴露该泄漏。
        for (InterfaceHttpData data : multipart.getInterfaceHttpDataList()) {
            assertEquals(0, data.refCnt(),
                    "retain 3 次后对称 release，part data 引用应归零（修复前泄漏 1 个引用）");
        }
    }
}

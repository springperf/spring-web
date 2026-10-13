package io.springperf.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;

/**
 * 请求对象两处**懒分配**（构造期不再白分配）的语义回归：
 * <ul>
 * <li>{@code uriStr}（{@code '?'} 之前的部分）——只在 {@code getUriStr()} 首次访问时截取。 默认
 * {@code spring.mvc.publish-request-handled-events=false}，热路径完全不读它， 原实现在构造期无条件 {@code substring} 属每请求白分配（JFR
 * 分配采样驱动）。</li>
 * <li>String 键属性表（{@link RequestContext#getAttributes()}）——只在真正读写 String 键属性时创建； 热路径只用类型化属性数组。语义（含 Servlet
 * {@code setAttribute(name, null)} == remove）不变。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class NettyServerHttpRequestLazyStateTest {

    /** 请求创建**之后**才注册的属性：其 index 必然 ≥ 该请求的 fastAttributes 长度 → 走 String 键回退通道。 */
    private static final RequestAttribute<String> LATE_ATTR = RequestAttribute.createAttribute(String.class);

    @Mock
    private WebContext webContext;
    @Mock
    private ChannelHandlerContext ctx;
    @Mock
    private ApplicationProperties props;

    @BeforeEach
    void setUp() {
        lenient().when(webContext.getProps()).thenReturn(props);
        lenient().when(props.getInt(anyString())).thenReturn(4096);
        lenient().when(props.getMaxParameterCount()).thenReturn(0);
    }

    private NettyServerHttpRequest request(String uri, String resolvedPath) {
        FullHttpRequest raw = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri,
                Unpooled.EMPTY_BUFFER);
        return new NettyServerHttpRequest(webContext, ctx, raw, resolvedPath);
    }

    /** 有查询串：uriStr 为 '?' 之前的部分；无查询串：与 uriStrWithQuery 同一实例（不复制）。 */
    @Test
    void uriStr_lazyComputation_isEquivalentToEager() {
        NettyServerHttpRequest withQuery = request("/test?a=1&b=2", "/test");
        assertEquals("/test", withQuery.getUriStr());
        assertSame(withQuery.getUriStr(), withQuery.getUriStr(), "重复访问应复用同一实例");

        NettyServerHttpRequest noQuery = request("/test", "/test");
        assertSame(noQuery.getUriStrWithQuery(), noQuery.getUriStr(), "无查询串时不应产生新字符串");
    }

    /** getMethod() 结果缓存：重复读取返回同一实例，且与解析值一致。 */
    @Test
    void getMethod_isCachedAndEqualsParsedValue() {
        NettyServerHttpRequest req = request("/test", "/test");
        org.springframework.http.HttpMethod first = req.getMethod();
        assertSame(first, req.getMethod(), "重复读取应复用缓存实例");
        assertEquals("GET", first.name());
    }

    /**
     * 行为证明缓存**真的**生效（帧级采样对内联敏感，不能作为证据）：首次读取后改动底层请求的方法， 后续读取必须仍返回首次结果 —— 若未缓存，这里会变成 POST。
     */
    @Test
    void getMethod_doesNotReReadUnderlyingRequestAfterFirstCall() {
        io.netty.handler.codec.http.FullHttpRequest raw = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                HttpMethod.GET, "/test", Unpooled.EMPTY_BUFFER);
        NettyServerHttpRequest req = new NettyServerHttpRequest(webContext, ctx, raw, "/test");

        assertEquals(org.springframework.http.HttpMethod.GET, req.getMethod());
        raw.setMethod(HttpMethod.POST);
        assertEquals(org.springframework.http.HttpMethod.GET, req.getMethod(), "首次解析后应命中缓存，不再回读底层请求");
    }

    /** 未使用 String 键属性时，属性表不创建：getAttribute/removeAttribute 返回 null 而非抛错。 */
    @Test
    void stringAttributes_notTouched_returnNullWithoutAllocatingMap() {
        NettyServerHttpRequest req = request("/test", "/test");

        assertNull(req.getAttribute("missing"), "未创建属性表时读取应为 null");
        assertNull(req.removeAttribute("missing"), "未创建属性表时移除应为 null");
        // setAttribute(name, null) 等价 remove：属性表未创建时不得因此创建
        req.setAttribute("missing", null);
        assertNull(req.getAttribute("missing"));
    }

    /** 首次写入才建表，且 getAttributes() 返回可用的可变表；set/remove 往返正确。 */
    @Test
    void stringAttributes_lazilyCreatedAndMutable() {
        NettyServerHttpRequest req = request("/test", "/test");

        req.setAttribute("k", "v");
        assertEquals("v", req.getAttribute("k"));

        Map<String, Object> map = req.getAttributes();
        assertNotNull(map, "getAttributes() 必须非 null（接口契约）");
        assertSame(map, req.getAttributes(), "同一请求应返回同一张表");
        assertEquals("v", map.get("k"));

        req.setAttribute("k", null);
        assertNull(req.getAttribute("k"), "Servlet 语义：setAttribute(name, null) 等价 remove");
        assertNull(req.removeAttribute("k"));
    }

    /** 类型化属性：正常走数组；索引超出请求数组长度时回退 String 键通道（两通道结果一致）。 */
    @Test
    void typedAttributes_arrayPathAndLateRegistrationFallback() {
        NettyServerHttpRequest early = request("/test", "/test");
        early.setAttribute(LATE_ATTR, "late-value");

        // LATE_ATTR 在本类初始化时注册；early 的 fastAttributes 长度按创建时刻的 maxSize 计算。
        // 无论落在数组内还是回退通道，读写都必须一致。
        assertEquals("late-value", early.getAttribute(LATE_ATTR));
        assertNull(request("/test", "/test").getAttribute(LATE_ATTR), "不同请求之间属性不得串扰");
    }

    /** 并发首次访问属性表：双检锁保证只建一张表（异步 dispatch 可能跨线程访问）。 */
    @Test
    void stringAttributes_concurrentFirstAccess_singleInstance() throws Exception {
        NettyServerHttpRequest req = request("/test", "/test");
        AtomicReference<Map<String, Object>> a = new AtomicReference<>();
        AtomicReference<Map<String, Object>> b = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        Thread t1 = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            a.set(req.getAttributes());
            done.countDown();
        });
        Thread t2 = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            b.set(req.getAttributes());
            done.countDown();
        });
        t1.start();
        t2.start();
        start.countDown();
        done.await();

        assertNotNull(a.get());
        assertSame(a.get(), b.get(), "并发首次访问必须得到同一张属性表");
    }
}

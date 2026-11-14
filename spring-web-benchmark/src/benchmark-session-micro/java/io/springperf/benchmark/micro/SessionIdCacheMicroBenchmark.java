package io.springperf.benchmark.micro;

import io.springperf.web.context.WebContext;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.support.servlet.PerfHttpServletRequest;
import io.springperf.web.support.servlet.session.PerfHttpSessionManager;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.springframework.http.HttpHeaders;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H1 微基准：getRequestedSessionId 客户端 id 解析缓存的收益。
 * <p>
 * 场景：一次请求内多处调用 getSession/isRequestedSessionIdValid/encodeSessionUrl，都会触发
 * getRequestedSessionId。改动前每次重新遍历 Cookie[] + 逐字符扫 URI + 大小写不敏感匹配；
 * 改动后只解析一次，后续命中 volatile 缓存。
 * </p>
 * <p>
 * 对照手法：legacy 版在每次调用前用反射把缓存标志清零，强制走真实的完整解析代码路径
 * （与改动前的逻辑等价，仅多了几纳秒的反射写）；optimized 版不清零，命中缓存。
 * </p>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
public class SessionIdCacheMicroBenchmark {

    private PerfHttpServletRequest request;
    private Field parsedFlag;
    /** 模拟一次请求内被多处调用的次数。 */
    private static final int CALLS_PER_REQUEST = 6;

    @Setup
    public void setup() throws Exception {
        WebContext webContext = mock(WebContext.class);
        PerfHttpSessionManager mgr = mock(PerfHttpSessionManager.class);
        when(mgr.getCookieName()).thenReturn("JSESSIONID");
        when(webContext.getWebComponent(PerfHttpSessionManager.class)).thenReturn(mgr);

        RequestContext requestContext = mock(RequestContext.class);
        HttpHeaders headers = new HttpHeaders();
        headers.set(io.netty.handler.codec.http.HttpHeaders.Names.COOKIE,
                "JSESSIONID=client-session-id-0123456789abcdef; theme=dark; locale=zh_CN; pref=a,b,c");

        WebServerHttpRequest delegate = mock(WebServerHttpRequest.class);
        when(delegate.getHeaders()).thenReturn(headers);
        when(delegate.getUriStr()).thenReturn("/api/orders/list/2026/11?status=active&page=1");
        when(delegate.getWebContext()).thenReturn(webContext);
        when(delegate.getRequestContext()).thenReturn(requestContext);

        request = new PerfHttpServletRequest(delegate);
        parsedFlag = PerfHttpServletRequest.class.getDeclaredField("clientSessionIdParsed");
        parsedFlag.setAccessible(true);
    }

    /** 改动前：每次调用都强制重新解析 cookie + URI（反射清缓存模拟原始未缓存路径）。 */
    @Benchmark
    public void legacy_reparseEveryCall(Blackhole bh) throws Exception {
        for (int i = 0; i < CALLS_PER_REQUEST; i++) {
            parsedFlag.setBoolean(request, false);
            bh.consume(request.getRequestedSessionId());
        }
    }

    /** 改动后：首次解析，其余命中缓存。 */
    @Benchmark
    public void optimized_parseOnceThenCached(Blackhole bh) {
        for (int i = 0; i < CALLS_PER_REQUEST; i++) {
            bh.consume(request.getRequestedSessionId());
        }
    }
}

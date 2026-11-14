package io.springperf.benchmark.micro;

import io.springperf.web.support.servlet.session.HttpSessionData;
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

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.concurrent.TimeUnit;

/**
 * spring-web-servlet 会话热路径微基准：量化两处优化的算法级收益。
 * <p>
 * H2：对比「每请求全量 JDK 序列化整个会话」（改动前）与「脏检查命中、跳过序列化」（改动后）。
 * 这是 P1 热点的 CPU 主体；文件 IO 部分受磁盘影响，不纳入微基准，但跳过序列化必然同时跳过两次文件 IO。
 * </p>
 * <p>
 * H1（sessionId 解析缓存）依赖完整的 WebServerHttpRequest/RequestContext 图，由同包的
 * {@link SessionIdCacheMicroBenchmark} 单独覆盖；本基准聚焦可直接构造的序列化脏检查部分。
 * </p>
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
public class SessionPerfMicroBenchmark {

    private static final class Payload implements Serializable {
        private static final long serialVersionUID = 1L;
        final String tenant;
        final long uid;
        final String token;

        Payload(String tenant, long uid, String token) {
            this.tenant = tenant;
            this.uid = uid;
            this.token = token;
        }
    }

    private HttpSessionData cleanSession;
    private HttpSessionData dirtySession;

    @Setup
    public void setup() {
        cleanSession = buildSession();
        // 模拟一次成功落盘：内容版本已回填，处于「干净」状态
        cleanSession.markPersisted(cleanSession.getContentVersion(), cleanSession.getLastAccessedTime());

        dirtySession = buildSession();
        // 业务写入使 contentVersion 前进，未落盘 → 脏
        dirtySession.setAttribute("user", new Payload("acme-corp", 123456789L, "jwt-token-value-0123456789"));
    }

    private static HttpSessionData buildSession() {
        HttpSessionData data = new HttpSessionData("session-id-0123456789abcdef", System.currentTimeMillis());
        data.setMaxInactiveInterval(1800);
        // 典型登录态会话：若干属性，整体序列化有实际成本
        data.setAttribute("user", new Payload("acme-corp", 123456789L, "jwt-token-value-0123456789"));
        data.setAttribute("roles", "ROLE_USER,ROLE_ADMIN,ROLE_AUDITOR");
        data.setAttribute("locale", "zh_CN");
        data.setAttribute("lastPage", "/api/dashboard/overview");
        return data;
    }

    /** 改动前：每个访问 session 的请求响应结束都无条件全量序列化。 */
    @Benchmark
    public void legacy_serializeEveryResponse(Blackhole bh) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(cleanSession);
        }
        bh.consume(bos.toByteArray());
    }

    /**
     * 改动后：先做脏检查，干净会话（纯读请求）直接跳过序列化。
     * 判定逻辑与 PerfHttpSessionManager.saveSessionAfterResponse 一致。
     */
    @Benchmark
    public void optimized_dirtyCheck(Blackhole bh) throws Exception {
        boolean contentDirty = cleanSession.getContentVersion() != cleanSession.getPersistedVersion();
        if (contentDirty) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeObject(cleanSession);
            }
            bh.consume(bos.toByteArray());
        } else {
            bh.consume(0);
        }
    }

    /** 对照：脏会话在改动后仍会立即序列化（确认优化没有牺牲写入语义）。 */
    @Benchmark
    public void optimized_dirtySessionStillSerializes(Blackhole bh) throws Exception {
        boolean contentDirty = dirtySession.getContentVersion() != dirtySession.getPersistedVersion();
        if (contentDirty) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeObject(dirtySession);
            }
            bh.consume(bos.toByteArray());
        } else {
            bh.consume(0);
        }
    }
}

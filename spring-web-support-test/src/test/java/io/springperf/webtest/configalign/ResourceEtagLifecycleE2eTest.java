package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ETag 生命周期 E2E（文件系统资源）：校验器必须能区分内容、随内容变化失效， 否则客户端会长期使用过期副本（拿到陈旧 304）。
 * <p>
 * ETag 由「最后修改时间 + 内容长度」派生（零 IO，避免每请求读盘算哈希）。 这意味着**长度与 mtime 都不变**的原地改写无法被感知——本类显式锁定该已知取舍， 便于后续若改为内容哈希时有对照。
 * </p>
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        ResourceEtagLifecycleE2eTest.EtagConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "spring.web.resources.cache.period=60" })
class ResourceEtagLifecycleE2eTest {

    /** 资源目录（配置类静态块创建，早于 addResourceHandlers 注册）。 */
    static final Path DIR = Path.of("target", "e2e-etag-dir").toAbsolutePath();

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    @BeforeEach
    void cleanDir() throws IOException {
        Files.createDirectories(DIR);
        try (Stream<Path> files = Files.list(DIR)) {
            files.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不影响后续断言（每个用例用独立文件名）
                }
            });
        }
    }

    private Response get(String path, String... headers) throws Exception {
        Request.Builder b = new Request.Builder().url("http://localhost:" + port + path).get();
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return CLIENT.newCall(b.build()).execute();
    }

    private String etagOf(String name) throws Exception {
        Response resp = get("/e2e-etag/" + name);
        try {
            assertEquals(200, resp.code(), "资源应可访问（先写入文件），实际 " + resp.code());
            String etag = resp.header("ETag");
            assertNotNull(etag, "文件系统资源应携带 ETag");
            return etag;
        } finally {
            resp.close();
        }
    }

    private void writeFile(String name, String content) throws IOException {
        Files.write(DIR.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void sameFile_repeatedRequests_keepSameEtag() throws Exception {
        writeFile("stable.txt", "stable-content");
        assertEquals(etagOf("stable.txt"), etagOf("stable.txt"), "同一文件多次请求 ETag 必须稳定（否则条件请求永不命中）");
    }

    @Test
    void differentLengths_produceDifferentEtags() throws Exception {
        writeFile("a.txt", "content-aaa");
        writeFile("b.txt", "content-bbbb");
        assertNotEquals(etagOf("a.txt"), etagOf("b.txt"), "长度不同 → ETag 必须不同");
    }

    @Test
    void contentChanged_oldEtagNoLongerMatches() throws Exception {
        writeFile("changed.txt", "version-one");
        String oldEtag = etagOf("changed.txt");

        Thread.sleep(1100); // 让 mtime 至少前进 1s（HTTP 日期粒度为秒）
        writeFile("changed.txt", "version-two-longer");

        Response resp = get("/e2e-etag/changed.txt", "If-None-Match", oldEtag);
        try {
            assertEquals(200, resp.code(), "内容已变化时旧 ETag 不得再命中 304（否则客户端永远拿到旧副本），实际 " + resp.code());
            assertNotEquals(oldEtag, resp.header("ETag"), "内容变化后应产生新 ETag");
            assertEquals("version-two-longer", resp.body().string());
        } finally {
            resp.close();
        }
    }

    @Test
    void contentChanged_newEtagThenMatches() throws Exception {
        writeFile("seq.txt", "first");
        Thread.sleep(1100);
        writeFile("seq.txt", "second");
        String newEtag = etagOf("seq.txt");

        Response resp = get("/e2e-etag/seq.txt", "If-None-Match", newEtag);
        try {
            assertEquals(304, resp.code(), "新 ETag 应立即可用于条件请求，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    /**
     * 已知取舍（非缺陷）：ETag = {@code f(lastModified 毫秒, contentLength)}，以免每请求读盘算内容哈希。
     * <p>
     * 因此 ETag 的区分力取决于 mtime 的毫秒分辨率：**长度相同且 mtime 落在同一毫秒**才可能碰撞 （正常写入几乎不可能命中，故实际风险可忽略；跨 URL 的 ETag 相同对以 URL 为键的缓存亦无影响）。
     * 比 HTTP Last-Modified 的秒级粒度更细，属可接受的取舍。若将来改为（带缓存的）内容哈希， 本用例会失败并提示更新语义。
     * </p>
     */
    @Test
    void sameLengthAndSameMillisecond_etagCollides_knownTradeoff() throws Exception {
        writeFile("collide-a.txt", "AAAAAAAA");
        writeFile("collide-b.txt", "BBBBBBBB");
        // 显式对齐两文件的 mtime，构造「同毫秒 + 同长度」这一唯一碰撞条件
        FileTime mtime = Files.getLastModifiedTime(DIR.resolve("collide-a.txt"));
        Files.setLastModifiedTime(DIR.resolve("collide-b.txt"), mtime);
        assertEquals(etagOf("collide-a.txt"), etagOf("collide-b.txt"),
                "同 mtime + 同长度 ⇒ ETag 相同（f(mtime, length) 派生的必然结果）");

        // 同 URL 原地改写：长度不变且 mtime 未被推进时同样无法区分
        String etagBefore = etagOf("collide-a.txt");
        writeFile("collide-a.txt", "CCCCCCCC");
        Files.setLastModifiedTime(DIR.resolve("collide-a.txt"), mtime);
        assertEquals(etagBefore, etagOf("collide-a.txt"), "长度与 mtime 均未变时 ETag 不变");
    }

    @TestConfiguration
    static class EtagConfig implements WebMvcConfigurer {

        static {
            try {
                Files.createDirectories(DIR);
            } catch (IOException e) {
                throw new IllegalStateException("无法创建 ETag E2E 资源目录 " + DIR, e);
            }
        }

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/e2e-etag/**").addResourceLocations(DIR.toUri().toString());
        }

        @Bean
        EtagNoopBean etagNoopBean() {
            return new EtagNoopBean();
        }
    }

    /** 占位 bean：确保配置类被实例化（静态块执行）。 */
    static class EtagNoopBean {
    }
}

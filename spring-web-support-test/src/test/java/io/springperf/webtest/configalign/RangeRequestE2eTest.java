package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 静态资源 byte range E2E（RFC 9110 §14）：
 * 普通请求宣告 {@code Accept-Ranges: bytes} 且带 Content-Length；单段 range → 206 + Content-Range；
 * 开区间/后缀区间；不可满足 → 416 + {@code Content-Range: bytes *&#47;len}；
 * {@code If-Range} 命中才走 206（否则整实体 200）；多段 range → {@code multipart/byteranges}；
 * 语法错误或段数超限（{@code server.http.max-ranges}，默认值与解析器上限 100 相同）回退整实体。
 *
 * <p>长度不硬编码：先取一次完整响应作为基准，再据此推导期望的片段内容。</p>
 */
@SpringBootTest(classes = ConfigAlignTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "spring.web.resources.add-mappings=true"
        })
class RangeRequestE2eTest {

    private static final String PATH = "/e2e-range.txt";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private Response get(String path, String... headers) throws Exception {
        Request.Builder b = new Request.Builder().url("http://localhost:" + port + path).get();
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return CLIENT.newCall(b.build()).execute();
    }

    /** 完整实体内容（同时作为所有分片断言的基准）。 */
    private String fullBody() throws Exception {
        Response resp = get(PATH);
        try {
            assertEquals(200, resp.code());
            return resp.body().string();
        } finally {
            resp.close();
        }
    }

    /** 取资源 Last-Modified（毫秒），供 If-Range 日期用例推导。 */
    private long fetchLastModified() throws Exception {
        Response resp = get(PATH);
        try {
            String lm = resp.header("Last-Modified");
            assertNotNull(lm, "静态资源应带 Last-Modified");
            return java.time.ZonedDateTime
                    .parse(lm, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
        } finally {
            resp.close();
        }
    }

    private static String rfc1123(long epochMilli) {
        return java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.Instant.ofEpochMilli(epochMilli).atZone(java.time.ZoneId.of("GMT")));
    }

    // ==================== 基线 ====================

    @Test
    void plainRequest_declaresAcceptRangesBytes() throws Exception {
        Response resp = get(PATH);
        try {
            assertEquals(200, resp.code());
            assertNotNull(resp.body().string());
        } finally {
            resp.close();
        }
        Response again = get(PATH);
        try {
            assertEquals("bytes", again.header("Accept-Ranges"),
                    "静态资源应宣告支持 byte range，实际 " + again.header("Accept-Ranges"));
        } finally {
            again.close();
        }
    }

    @Test
    void plainRequest_hasContentLength() throws Exception {
        Response resp = get(PATH);
        try {
            int len = resp.body().string().length();
            assertEquals(String.valueOf(len), resp.header("Content-Length"),
                    "完整响应应带 Content-Length（而非只有 chunked），实际 headers=" + resp.headers());
        } finally {
            resp.close();
        }
    }

    // ==================== 单段 range ====================

    @Test
    void firstBytesRange_returns206WithContentRange() throws Exception {
        String full = fullBody();
        Response resp = get(PATH, "Range", "bytes=0-9");
        try {
            assertEquals(206, resp.code(), "满足的 Range 应返回 206，实际 " + resp.code());
            assertEquals("bytes 0-9/" + full.length(), resp.header("Content-Range"),
                    "Content-Range 应描述实际区间，实际 " + resp.header("Content-Range"));
            assertEquals("10", resp.header("Content-Length"), "206 应有精确 Content-Length");
            assertEquals(full.substring(0, 10), resp.body().string(), "返回内容应为请求区间");
        } finally {
            resp.close();
        }
    }

    @Test
    void openEndedRange_returnsRemainder() throws Exception {
        String full = fullBody();
        int start = 10;
        Response resp = get(PATH, "Range", "bytes=" + start + "-");
        try {
            assertEquals(206, resp.code());
            assertEquals("bytes " + start + "-" + (full.length() - 1) + "/" + full.length(),
                    resp.header("Content-Range"));
            assertEquals(full.substring(start), resp.body().string(),
                    "bytes=N- 应返回从 N 到结尾的全部内容");
        } finally {
            resp.close();
        }
    }

    @Test
    void suffixRange_returnsLastBytes() throws Exception {
        String full = fullBody();
        int n = 8;
        Response resp = get(PATH, "Range", "bytes=-" + n);
        try {
            assertEquals(206, resp.code());
            assertEquals("bytes " + (full.length() - n) + "-" + (full.length() - 1) + "/" + full.length(),
                    resp.header("Content-Range"));
            assertEquals(full.substring(full.length() - n), resp.body().string(),
                    "bytes=-N 应返回末尾 N 字节");
        } finally {
            resp.close();
        }
    }

    @Test
    void singleByteAtLastPosition_returns206() throws Exception {
        String full = fullBody();
        int last = full.length() - 1;
        Response resp = get(PATH, "Range", "bytes=" + last + "-" + last);
        try {
            assertEquals(206, resp.code());
            assertEquals("1", resp.header("Content-Length"));
            assertEquals(full.substring(last), resp.body().string());
        } finally {
            resp.close();
        }
    }

    // ==================== 不可满足 / 回退 ====================

    @Test
    void unsatisfiableRange_returns416WithUnsatisfiedContentRange() throws Exception {
        String full = fullBody();
        Response resp = get(PATH, "Range", "bytes=" + (full.length() + 100) + "-");
        try {
            assertEquals(416, resp.code(),
                    "起点超出实体长度应返回 416，实际 " + resp.code());
            assertEquals("bytes */" + full.length(), resp.header("Content-Range"),
                    "416 应带 Content-Range: bytes */len，实际 " + resp.header("Content-Range"));
        } finally {
            resp.close();
        }
    }

    @Test
    void multiRange_servesMultipartByteranges() throws Exception {
        // 多段 range → multipart/byteranges（RFC 9110 §14.4，对齐 Spring/Tomcat）：
        // 206 + 分段 Content-Range + 精确总 Content-Length + 段内容与顺序保持
        String full = fullBody();
        int len = full.length();
        Response resp = get(PATH, "Range", "bytes=0-1,5-6");
        try {
            assertEquals(206, resp.code(), "多段 range 应返回 206，实际 " + resp.code());
            // 线上头为紧凑序列化（";boundary=" 无空格），统一去空格后比对
            String contentType = resp.header("Content-Type").replace(" ", "");
            assertNotNull(contentType);
            assertTrue(contentType.startsWith("multipart/byteranges;boundary="),
                    "应声明 multipart/byteranges 及 boundary，实际 " + contentType);
            String boundary = contentType.substring(contentType.indexOf('=') + 1);
            String body = resp.body().string();

            String expect = "--" + boundary + "\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Range: bytes 0-1/" + len + "\r\n\r\n"
                    + full.substring(0, 2) + "\r\n"
                    + "--" + boundary + "\r\n"
                    + "Content-Type: text/plain\r\n"
                    + "Content-Range: bytes 5-6/" + len + "\r\n\r\n"
                    + full.substring(5, 7) + "\r\n"
                    + "--" + boundary + "--\r\n";
            assertEquals(expect, body, "分段体结构、顺序与内容必须精确匹配");
            assertEquals(String.valueOf(body.length()), resp.header("Content-Length"),
                    "多段体的 Content-Length 应为精确总长，实际 headers=" + resp.headers());
        } finally {
            resp.close();
        }
    }

    @Test
    void multiRange_threeSegments_allServedInOrder() throws Exception {
        String full = fullBody();
        int len = full.length();
        Response resp = get(PATH, "Range", "bytes=0-0,3-4,-2");
        try {
            assertEquals(206, resp.code(), "实际 " + resp.code());
            String boundary = resp.header("Content-Type").split("boundary=")[1].trim();
            String body = resp.body().string();
            // 请求序即响应序：0-0、3-4、后缀区间 -2 → 末尾 2 字节
            assertTrue(body.contains("Content-Range: bytes 0-0/" + len + "\r\n\r\n" + full.substring(0, 1) + "\r\n"));
            assertTrue(body.contains("Content-Range: bytes 3-4/" + len + "\r\n\r\n" + full.substring(3, 5) + "\r\n"));
            assertTrue(body.contains("Content-Range: bytes " + (len - 2) + "-" + (len - 1) + "/" + len
                    + "\r\n\r\n" + full.substring(len - 2) + "\r\n"));
            assertTrue(body.endsWith("--" + boundary + "--\r\n"), "应以收尾边界结束，实际 " + body);
        } finally {
            resp.close();
        }
    }

    @Test
    void multiRange_anyUnsatisfiable_returns416() throws Exception {
        // 任一段不可满足 → 整体 416（对齐 Spring：任一 region 越界即整体不可满足）
        String full = fullBody();
        Response resp = get(PATH, "Range", "bytes=0-1," + (full.length() + 50) + "-");
        try {
            assertEquals(416, resp.code(),
                    "任一段越界应整体 416，实际 " + resp.code());
            assertEquals("bytes */" + full.length(), resp.header("Content-Range"),
                    "416 应带 Content-Range: bytes */len，实际 " + resp.header("Content-Range"));
        } finally {
            resp.close();
        }
    }

    /** 构造 N 段（每段 {@code 0-0}，全部可满足——避免依赖资源实际长度）的 Range 头。 */
    private static String repeatedSingleByteRanges(int segments) {
        StringBuilder sb = new StringBuilder("bytes=");
        for (int i = 0; i < segments; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("0-0");
        }
        return sb.toString();
    }

    @Test
    void multiRange_atLimit_stillServedAsMultipart() throws Exception {
        // 边界：恰好等于 server.http.max-ranges（默认 100）→ 仍走 multipart/byteranges
        Response resp = get(PATH, "Range", repeatedSingleByteRanges(100));
        try {
            assertEquals(206, resp.code(), "恰好等于上限应仍返回 206，实际 " + resp.code());
            assertTrue(resp.header("Content-Type").replace(" ", "")
                            .startsWith("multipart/byteranges;boundary="),
                    "实际 Content-Type=" + resp.header("Content-Type"));
        } finally {
            resp.close();
        }
    }

    @Test
    void multiRange_aboveLimit_fallsBackToFullEntity() throws Exception {
        // 101 段超过有效上限（默认 100 与 Spring HttpRange 解析器上限重合）→ 按 RFC 9110 §14.2
        // 「服务器可忽略 Range」返回整实体 200：不截断段数（截断会让客户端拿到与请求不符的表示）、
        // 也不回 416（请求本身合法）。本键**收紧**后的行为见 RangeLimitE2eTest。
        String full = fullBody();
        Response resp = get(PATH, "Range", repeatedSingleByteRanges(101));
        try {
            assertEquals(200, resp.code(),
                    "超过 server.http.max-ranges 应忽略 Range 返回整实体，实际 " + resp.code());
            String contentType = resp.header("Content-Type");
            assertTrue(contentType == null || !contentType.contains("multipart/byteranges"),
                    "不得返回 multipart/byteranges，实际 " + contentType);
            assertEquals(String.valueOf(full.length()), resp.header("Content-Length"),
                    "整实体应带完整 Content-Length");
            assertEquals(full, resp.body().string(), "应返回完整实体内容");
        } finally {
            resp.close();
        }
    }

    @Test
    void headMultiRange_returns206MetadataWithoutBody() throws Exception {
        String full = fullBody();
        Response resp = CLIENT.newCall(new Request.Builder()
                .url("http://localhost:" + port + PATH)
                .header("Range", "bytes=0-1,5-6")
                .head().build()).execute();
        try {
            assertEquals(206, resp.code());
            assertTrue(resp.header("Content-Type").replace(" ", "")
                    .startsWith("multipart/byteranges;boundary="));
            int declared = Integer.parseInt(resp.header("Content-Length"));
            assertEquals("", resp.body().string(), "HEAD 不应返回 body");
            assertTrue(declared > 0, "HEAD 应保留多段体总长元数据");
        } finally {
            resp.close();
        }
    }

    @Test
    void malformedRange_ignoredAndServesFullEntity() throws Exception {
        String full = fullBody();
        Response resp = get(PATH, "Range", "bytes=abc-def");
        try {
            assertEquals(200, resp.code(), "语法错误的 Range 应被忽略（200），实际 " + resp.code());
            assertEquals(full, resp.body().string());
        } finally {
            resp.close();
        }
    }

    // ==================== If-Range ====================

    @Test
    void ifRange_etagMatches_returns206() throws Exception {
        Response base = get(PATH);
        String etag;
        try {
            etag = base.header("ETag");
            base.body().string();
        } finally {
            base.close();
        }
        assertNotNull(etag, "静态资源应带 ETag");

        Response resp = get(PATH, "Range", "bytes=0-4", "If-Range", etag);
        try {
            assertEquals(206, resp.code(),
                    "If-Range 的 ETag 命中时应返回 206，实际 " + resp.code());
            assertTrue(resp.body().string().length() == 5);
        } finally {
            resp.close();
        }
    }

    @Test
    void ifRange_etagStale_returnsFullEntity() throws Exception {
        String full = fullBody();
        Response resp = get(PATH, "Range", "bytes=0-4", "If-Range", "\"stale-etag\"");
        try {
            assertEquals(200, resp.code(),
                    "If-Range 未命中时应忽略 Range 返回整实体，实际 " + resp.code());
            assertEquals(full, resp.body().string());
        } finally {
            resp.close();
        }
    }

    @Test
    void ifRange_httpDateNotOlderThanLastModified_returns206() throws Exception {
        // HTTP-date 形式的 If-Range：资源在客户端缓存时刻之后【未】修改 → 允许 Range（206）
        long lastModified = fetchLastModified();
        String date = rfc1123(lastModified + 60_000);
        Response resp = get(PATH, "Range", "bytes=0-4", "If-Range", date);
        try {
            assertEquals(206, resp.code(),
                    "If-Range 日期晚于资源修改时间应允许 Range，实际 " + resp.code());
            assertEquals("bytes 0-4/" + fullBody().length(), resp.header("Content-Range"));
        } finally {
            resp.close();
        }
    }

    @Test
    void ifRange_httpDateStale_returnsFullEntity() throws Exception {
        // 资源在客户端缓存时刻之后【已】修改 → 必须忽略 Range 返回整实体
        // （否则客户端会把新版实体的片段拼进旧版缓存）
        long lastModified = fetchLastModified();
        String staleDate = rfc1123(lastModified - 60_000);
        String full = fullBody();
        Response resp = get(PATH, "Range", "bytes=0-4", "If-Range", staleDate);
        try {
            assertEquals(200, resp.code(),
                    "If-Range 日期早于资源修改时间应忽略 Range，实际 " + resp.code());
            assertEquals(full, resp.body().string());
        } finally {
            resp.close();
        }
    }

    @Test
    void ifRange_weakEtag_neverMatches() throws Exception {
        // RFC 9110 §13.1.5：If-Range 的实体标签比较是【强】比较——W/ 弱标签永不相匹配，
        // 否则弱化的缓存副本会被拿去做区间拼接
        String full = fullBody();
        Response resp = get(PATH, "Range", "bytes=0-4", "If-Range", "W/\"anything\"");
        try {
            assertEquals(200, resp.code(), "弱 ETag 不得通过 If-Range 强比较，实际 " + resp.code());
            assertEquals(full, resp.body().string());
        } finally {
            resp.close();
        }
    }

    @Test
    void ifRange_malformedValue_ignoresRange() throws Exception {
        // 非 ETag 非 HTTP-date 的畸形值：无法评估 → 忽略 Range 返回整实体（不得 5xx/416）
        String full = fullBody();
        Response resp = get(PATH, "Range", "bytes=0-4", "If-Range", "not-a-date-or-etag");
        try {
            assertEquals(200, resp.code(), "实际 " + resp.code());
            assertEquals(full, resp.body().string());
        } finally {
            resp.close();
        }
    }

    // ==================== HEAD ====================

    @Test
    void headWithRange_returns206HeadersWithoutBody() throws Exception {
        String full = fullBody();
        Response resp = CLIENT.newCall(new Request.Builder()
                .url("http://localhost:" + port + PATH)
                .header("Range", "bytes=0-9")
                .head().build()).execute();
        try {
            assertEquals(206, resp.code(), "HEAD + Range 应返回 206 头，实际 " + resp.code());
            assertEquals("bytes 0-9/" + full.length(), resp.header("Content-Range"));
            assertEquals("10", resp.header("Content-Length"));
            assertEquals("", resp.body().string(), "HEAD 不应返回 body");
        } finally {
            resp.close();
        }
    }

    @Test
    void multiRangeOnKeepAlive_nextRequestUnaffected() throws Exception {
        // keep-alive 帧边界回归：多段 206 的总 Content-Length 若算错，
        // 同连接的下一个响应会错位/丢失——这是多段体分帧正确性的硬校验
        String full = fullBody();
        java.net.Socket s = new java.net.Socket("localhost", port);
        s.setSoTimeout(5000);
        java.io.OutputStream out = s.getOutputStream();
        out.write(("GET " + PATH + " HTTP/1.1\r\nHost: localhost\r\nRange: bytes=0-1,5-6\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.flush();
        String first = readOneResponse(s);
        assertTrue(first.startsWith("HTTP/1.1 206"), "实际:\n" + first);
        // 同连接第二个请求：普通 GET，必须完整解析
        out.write(("GET " + PATH + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.flush();
        String second = readOneResponse(s);
        assertTrue(second.startsWith("HTTP/1.1 200"), "同连接后续请求应正常，实际:\n" + second);
        assertTrue(second.contains(full), "第二响应 body 应完整，实际:\n" + second);
        s.close();
    }

    /** keep-alive 连接上精确读取一个完整响应（按头边界 + Content-Length）。 */
    private static String readOneResponse(java.net.Socket s) throws Exception {
        java.io.InputStream in = s.getInputStream();
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int c = in.read();
            if (c < 0) {
                return head.toString();
            }
            head.append((char) c);
        }
        int cl = -1;
        for (String line : head.toString().split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                cl = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        StringBuilder body = new StringBuilder();
        if (cl > 0) {
            byte[] buf = new byte[cl];
            int read = 0;
            while (read < cl) {
                int n = in.read(buf, read, cl - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            body.append(new String(buf, 0, read, java.nio.charset.StandardCharsets.UTF_8));
        }
        return head.toString() + body;
    }
}

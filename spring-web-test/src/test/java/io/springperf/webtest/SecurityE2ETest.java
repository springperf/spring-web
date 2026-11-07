package io.springperf.webtest;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E 网络安全回归：multipart 大小限制、路径遍历拦截、CORS 来源校验、异常信息不泄露。
 * <p>对应安全审计修复：multipart 此前绕过 max-content-length 导致磁盘/内存耗尽 DoS。</p>
 */
public class SecurityE2ETest extends BaseE2ETest {

    private String baseUrl() {
        return url("/api");
    }

    /** 发送原始 HTTP/1.1 请求，返回响应状态码（用于构造非常规请求头）。 */
    private int rawHttpExchange(String rawRequest) throws Exception {
        try (java.net.Socket socket = new java.net.Socket("localhost", serverPort)) {
            socket.setSoTimeout(5000);
            java.io.OutputStream out = socket.getOutputStream();
            out.write(rawRequest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            return readStatus(socket.getInputStream());
        }
    }

    /** 从响应头中解析状态码；连接被直接关闭（无响应）时返回 0。 */
    private static int readStatus(java.io.InputStream in) throws Exception {
        byte[] buf = new byte[4096];
        StringBuilder sb = new StringBuilder();
        int n;
        while ((n = in.read(buf)) != -1) {
            sb.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
            if (sb.indexOf("\r\n\r\n") >= 0) {
                break;
            }
        }
        String head = sb.toString();
        int sp1 = head.indexOf(' ');
        int sp2 = sp1 >= 0 ? head.indexOf(' ', sp1 + 1) : -1;
        if (sp2 < 0) {
            return 0;
        }
        return Integer.parseInt(head.substring(sp1 + 1, sp2).trim());
    }

    /* ==================== multipart 大小限制 ==================== */

    @Test
    void multipartOversizedContentLength_rejectedWith413() throws Exception {
        // 服务端在收到请求头后按 Content-Length 提前 fast-fail 返回 413（REQUEST_ENTITY_TOO_LARGE）。
        // 用原始 Socket 发送"声明超大 Content-Length + 极小 body"，确定性验证拒绝路径，
        // 避免真实上传 4MB+ body 时客户端写超时导致的偶发失败。
        String requestLine = "POST /api/upload/db-req HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Type: multipart/form-data; boundary=boundary\r\n"
                + "Content-Length: 5242880\r\n"   // 5MB > 默认 4MB 限制
                + "Connection: close\r\n"
                + "\r\n"
                + "--boundary\r\n"
                + "Content-Disposition: form-data; name=\"x\"\r\n\r\n"
                + "y\r\n"
                + "--boundary--\r\n";

        int status = rawHttpExchange(requestLine);
        assertEquals(413, status, "超大 Content-Length 的 multipart 请求必须被 413 拒绝，实际状态码: " + status);
    }

    @Test
    void multipartChunkedBody_processed() throws Exception {
        // 正面对照：chunked（无 Content-Length）的正常 multipart 必须被完整解析。
        // 没有这条，下面的超限用例无法区分"被 413 拒绝"与"chunked 请求根本没被读取"。
        String payload = "--boundary\r\nContent-Disposition: form-data; name=\"projectId\"\r\n\r\n7\r\n"
                + "--boundary\r\nContent-Disposition: form-data; name=\"seq\"\r\n\r\n1\r\n--boundary--\r\n";
        String raw = rawChunkedMultipart(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8), 4096);
        assertTrue(raw.contains("\"projectId\":7"), "chunked multipart 应被完整解析，实际响应: " + raw);
    }

    @Test
    void multipartChunkedBody_exceedingLimit_rejectedWith413() throws Exception {
        // 无 Content-Length（chunked）时无法按头 fast-fail：必须由流式聚合层在累计超限时兜住 413。
        // 这正是审计修复的 DoS 面 —— 只测头部的 fast-fail 无法证明流式路径也受同一上限保护。
        String prefix = "--boundary\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\n";
        byte[] payload = new byte[4 * 1024 * 1024 + 64 * 1024];
        java.util.Arrays.fill(payload, (byte) 'y');
        System.arraycopy(prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, payload, 0, prefix.length());
        String raw = rawChunkedMultipart(payload, 8192);
        assertTrue(raw.startsWith("HTTP/1.1 413"), "chunked multipart 累计超限必须被 413 拒绝，实际响应: " + raw);
    }

    /** 以 chunked 方式发送给定 payload，返回服务端原始响应文本（连接被 RST 时返回空串）。 */
    private String rawChunkedMultipart(byte[] payload, int chunkSize) throws Exception {
        java.nio.charset.Charset ascii = java.nio.charset.StandardCharsets.US_ASCII;
        try (java.net.Socket socket = new java.net.Socket("localhost", serverPort)) {
            socket.setSoTimeout(5000);
            java.io.OutputStream out = socket.getOutputStream();
            java.io.InputStream in = socket.getInputStream();
            // 边写边读：服务端回 413 后立即关闭连接，若响应没被及时读走，RST 会把它冲掉
            java.util.concurrent.atomic.AtomicReference<String> rawResp = new java.util.concurrent.atomic.AtomicReference<>(
                    "");
            Thread reader = new Thread(() -> {
                try {
                    java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int r;
                    while ((r = in.read(b)) != -1 && buf.size() < 8192) {
                        buf.write(b, 0, r);
                    }
                    rawResp.set(new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                    // 连接被 RST 时读不到响应，保留空串由断言给出明确失败
                }
            });
            reader.setDaemon(true);
            reader.start();

            String head = "POST /api/upload/db-req HTTP/1.1\r\n" + "Host: localhost\r\n"
                    + "Content-Type: multipart/form-data; boundary=boundary\r\n" + "Transfer-Encoding: chunked\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(ascii));
            out.flush();
            try {
                for (int offset = 0; offset < payload.length; offset += chunkSize) {
                    int size = Math.min(chunkSize, payload.length - offset);
                    out.write((Integer.toHexString(size) + "\r\n").getBytes(ascii));
                    out.write(payload, offset, size);
                    out.write("\r\n".getBytes(ascii));
                }
                out.write("0\r\n\r\n".getBytes(ascii));
                out.flush();
            } catch (java.io.IOException ignored) {
                // 服务端累计超限后会先回 413 再关闭连接，此后写入失败属预期
            }
            reader.join(10000);
            return rawResp.get();
        }
    }

    @Test
    void multipartNormalBody_processed() throws Exception {
        RequestBody part = RequestBody.create("small-content", MediaType.parse("text/plain"));
        RequestBody multipart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("projectId", "1")
                .addFormDataPart("seq", "1")
                .addFormDataPart("dbVersionList", "1.0")
                .addFormDataPart("file", "small.txt", part)
                .build();

        Request req = new Request.Builder()
                .url(baseUrl() + "/upload/db-req")
                .post(multipart)
                .build();

        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code(), "正常 multipart 请求应正常处理");
            String body = resp.body().string();
            Map<String, Object> result = com.alibaba.fastjson2.JSON.parseObject(body, Map.class);
            assertEquals(1, ((Number) result.get("projectId")).intValue(), "实际: " + body);
            assertEquals(1, ((Number) result.get("seq")).intValue(), "实际: " + body);
            assertEquals("1.0", result.get("dbVersionList"), "实际: " + body);
            assertEquals("small.txt", result.get("fileOriginalFilename"), "实际: " + body);
            assertEquals(13, ((Number) result.get("fileSize")).intValue(), "实际: " + body);
        }
    }

    /* ==================== 路径遍历拦截 ==================== */

    @Test
    void staticResource_dotDotTraversal_rejected() throws Exception {
        // 全部改用编码后的路径：OkHttp 会规范化明文 "../"（见 StaticResourceSecurityTest 的说明），
        // 不编码的 payload 根本到不了服务端，测的只是客户端 URL 规范化。
        for (String path : new String[] { "/static/..%2f..%2fetc%2fpasswd", "/static/%2e%2e/%2e%2e/etc/passwd",
                "/static/....//....//etc/passwd", "/static/..%5c..%5cwindows%5cwin.ini",
                "/static/%252e%252e%252fetc%252fpasswd" }) {
            Request req = new Request.Builder().url(baseUrl() + path).get().build();
            try (Response resp = CLIENT.newCall(req).execute()) {
                String body = resp.body().string();
                assertEquals(404, resp.code(), "路径遍历请求应被 404 拒绝: " + path + " body=" + body);
                assertFalse(body.contains("root:"), "路径遍历不得泄露 /etc/passwd: " + path);
                assertFalse(body.contains("[fonts]"), "路径遍历不得泄露 win.ini: " + path);
                assertFalse(body.contains("Hello, Static Resource!"), "路径遍历不得返回静态资源正文: " + path);
            }
        }
    }

    @Test
    void staticResource_normalFile_served() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/static/test.txt").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("Hello, Static Resource!", resp.body().string().trim(), "正常静态资源应返回完整内容");
        }
    }

    /* ==================== CORS 来源校验 ==================== */

    @Test
    void cors_allowedOrigin_echoedExactly() throws Exception {
        // 所有映射由 WebTestConfig 的全局 CorsRegistration（/**）统一配置
        // （allowedOrigins=http://example.com、allowCredentials=true）。
        // 用非重定向端点：/demo/echo 的 GET 返回 302，断言会读到 302 响应头而非业务响应。
        Request req = new Request.Builder().url(baseUrl() + "/core/bytes").header("Origin", "http://example.com").get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("http://example.com", resp.header("Access-Control-Allow-Origin"),
                    "允许的来源应精确回显，实际头: " + resp.headers());
            assertEquals("true", resp.header("Access-Control-Allow-Credentials"),
                    "allowCredentials=true 时应回写 ACAC，实际头: " + resp.headers());
            assertTrue(String.join(",", resp.headers("Vary")).contains("Origin"), "CORS 响应应带 Vary: Origin");
        }
    }

    @Test
    void cors_disallowedOrigin_notEchoed() throws Exception {
        // 不在白名单的来源：不得回显来源，也不得回写 ACAC。
        // 原断言是 "acao == null || example.com" 的析取，把"固定回显白名单值"也判为通过，
        // 无法发现"不校验 Origin 直接吐固定 ACAO"的实现。
        Request req = new Request.Builder().url(baseUrl() + "/core/bytes").header("Origin", "http://evil.example.com")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            // 与 Spring 的 DefaultCorsProcessor 一致：来源不被允许时直接拒绝（403），
            // 且不得回显任何 ACAO/ACAC
            assertEquals(403, resp.code(), "未授权来源应被 CORS 层拒绝，实际: " + resp.code());
            assertNull(resp.header("Access-Control-Allow-Origin"), "未授权来源不得回显 ACAO，实际头: " + resp.headers());
            assertNull(resp.header("Access-Control-Allow-Credentials"), "未授权来源不得回写 ACAC，实际头: " + resp.headers());
        }
    }

    /* ==================== 异常信息不泄露 ==================== */

    @Test
    void unhandledExceptionResponse_doesNotLeakDetails() throws Exception {
        // 兜底 handler 返回 400 + {"error":"bad argument","from":"global-handler"}（GlobalExceptionHandler），
        // 框架契约 = 不泄露异常类名与堆栈帧。只做负向断言时，200 空响应也能通过。
        Request req = new Request.Builder().url(baseUrl() + "/core/exception/illegal-argument").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            String body = resp.body().string();
            assertEquals(400, resp.code(), "应走全局兜底 handler 返回 400，实际: " + body);
            Map<String, Object> result = com.alibaba.fastjson2.JSON.parseObject(body, Map.class);
            assertEquals("bad argument", result.get("error"), "应回显异常消息但不泄露类名，实际: " + body);
            assertEquals("global-handler", result.get("from"), "实际: " + body);
            assertFalse(body.contains("IllegalArgumentException"), "响应不应泄露异常类名: " + body);
            assertFalse(body.contains("at io.springperf"), "响应不应泄露堆栈帧: " + body);
            assertFalse(body.contains("Exception"), "响应不应泄露异常类名/堆栈: " + body);
        }
    }
}
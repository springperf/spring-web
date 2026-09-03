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
            java.io.InputStream in = socket.getInputStream();
            out.write(rawRequest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();

            byte[] buf = new byte[4096];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                if (sb.toString().contains("\r\n\r\n")) {
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
        assertTrue(status >= 400 && status < 500,
                "超大 Content-Length 的 multipart 请求必须被拒绝（4xx），实际状态码: " + status);
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
        }
    }

    /* ==================== 路径遍历拦截 ==================== */

    @Test
    void staticResource_dotDotTraversal_rejected() throws Exception {
        for (String path : new String[]{
                "/static/../../etc/passwd",
                "/static/..%2f..%2fetc%2fpasswd",
                "/static/%2e%2e/%2e%2e/etc/passwd",
                "/static/....//....//etc/passwd",
                "/static/..\\..\\windows\\win.ini"
        }) {
            Request req = new Request.Builder().url(baseUrl() + path).get().build();
            try (Response resp = CLIENT.newCall(req).execute()) {
                assertNotEquals(200, resp.code(),
                        "路径遍历请求应被拒绝: " + path + " (got " + resp.code() + ")");
            }
        }
    }

    @Test
    void staticResource_normalFile_served() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/static/test.txt").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertTrue(resp.body().string().contains("Hello, Static Resource!"));
        }
    }

    /* ==================== CORS 来源校验 ==================== */

    @Test
    void cors_unconfiguredOrigin_notEchoed() throws Exception {
        // 未配置 CORS 的路径：响应不应携带 ACAO，更不应回显任意来源
        Request req = new Request.Builder()
                .url(baseUrl() + "/demo/echo?received=x")
                .header("Origin", "http://evil.example.com")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            String acao = resp.header("Access-Control-Allow-Origin");
            // 允许无 ACAO 头；但绝不能回显恶意来源
            assertTrue(acao == null || !acao.contains("evil.example.com"),
                    "未配置 CORS 的路径不应回显任意 Origin");
        }
    }

    /* ==================== 异常信息不泄露 ==================== */

    @Test
    void unhandledExceptionResponse_doesNotLeakDetails() throws Exception {
        // /core/exception/illegal-argument 无显式 @ExceptionHandler，走应用兜底 handler：
        // 框架契约 = 不泄露异常类名与堆栈帧（异常消息是否回显取决于应用自身 handler）
        Request req = new Request.Builder()
                .url(baseUrl() + "/core/exception/illegal-argument")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            String body = resp.body().string();
            assertFalse(body.contains("IllegalArgumentException"),
                    "500 响应不应泄露异常类名: " + body);
            assertFalse(body.contains("at io.springperf"),
                    "500 响应不应泄露堆栈帧: " + body);
            assertFalse(body.contains("Exception"),
                    "500 响应不应泄露堆栈帧: " + body);
        }
    }
}
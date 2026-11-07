package io.springperf.webtest;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actuator 管理端口冲突测试。
 * <p>
 * 验证 {@code management.server.port == server.port} 时应用启动失败。 使用动态空闲端口而非固定端口，避免端口被占用导致的假失败。
 * </p>
 */
public class ManagementPortConflictTest {

    @Test
    void sameManagementPort_shouldFailToStart() {
        int port = freePort();
        Exception ex = assertThrows(Exception.class, () -> SpringApplication.run(TestApplication.class,
                "--server.port=" + port, "--management.server.port=" + port, "--server.servlet.context-path=/api"));
        // 验证异常信息包含端口冲突描述
        assertTrue(containsMessage(ex, "must be different from server.port"), "异常信息应包含端口冲突描述, 实际: " + messageChain(ex));
    }

    @Test
    void differentManagementPort_shouldStartSuccessfully() {
        // 验证不同端口时启动正常（不抛异常）
        int serverPort = freePort();
        int mgmtPort;
        do {
            mgmtPort = freePort();
        } while (mgmtPort == serverPort);
        final int finalMgmtPort = mgmtPort;

        assertDoesNotThrow(() -> {
            try (org.springframework.context.ConfigurableApplicationContext ctx = SpringApplication.run(
                    TestApplication.class, "--server.port=" + serverPort, "--management.server.port=" + finalMgmtPort,
                    "--server.servlet.context-path=/api")) {
                assertTrue(ctx.isRunning());
                // 仅 ctx.isRunning() 无法证明两个端口真的绑定并提供 HTTP 服务
                OkHttpClient client = new OkHttpClient.Builder().connectTimeout(java.time.Duration.ofSeconds(3))
                        .readTimeout(java.time.Duration.ofSeconds(5)).build();
                try (Response r = client.newCall(new Request.Builder()
                        .url("http://localhost:" + serverPort + "/api/core/bytes").get().build()).execute()) {
                    assertEquals(200, r.code(), "主端口应提供业务端点，实际: " + r.code());
                    assertEquals("Hello, Bytes!", r.body().string(), "主端口响应内容应正确");
                }
                try (Response r = client.newCall(new Request.Builder()
                        .url("http://localhost:" + finalMgmtPort + "/actuator/health").get().build()).execute()) {
                    assertEquals(200, r.code(), "管理端口应提供 health 端点，实际: " + r.code());
                    assertEquals("UP", com.alibaba.fastjson2.JSON.parseObject(r.body().string()).getString("status"));
                }
            }
        });
    }

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 递归搜索异常链中是否包含关键词 */
    private static boolean containsMessage(Throwable ex, String keyword) {
        if (ex == null)
            return false;
        if (ex.getMessage() != null && ex.getMessage().contains(keyword))
            return true;
        return containsMessage(ex.getCause(), keyword);
    }

    /** 拼接完整异常链信息，用于断言失败时的诊断输出 */
    private static String messageChain(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable current = ex;
        while (current != null) {
            sb.append("[").append(current.getClass().getSimpleName()).append("] ").append(current.getMessage())
                    .append(" | ");
            current = current.getCause();
        }
        return sb.toString();
    }
}

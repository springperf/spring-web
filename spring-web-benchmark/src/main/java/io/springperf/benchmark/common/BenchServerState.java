package io.springperf.benchmark.common;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.Socket;
import java.util.Properties;

/**
 * 每个 JMH fork 启动一个隔离的 Spring Boot 服务器实例。
 * 由具体 Benchmark 类在 @Setup/@TearDown 中手动调用 start()/stop()。
 * <p>
 * 使用 {@link WebServerInitializedEvent} 监听器获取实际绑定端口，不依赖
 * {@code local.server.port} 属性（NettyHttpServer 不设置该属性）。
 * <p>
 * 首次绑定失败时自动重试（最多 3 次，1s 间隔），兼容 TIME_WAIT 场景。
 * 若重试后仍然失败，则使用随机端口 fallback。
 * <p>
 * stop() 使用端口可用性轮询而非固定 sleep，确保释放确认后才返回。
 */
public class BenchServerState {

    private final Class<?> applicationClass;
    private final Properties defaultProperties;
    /** 最大端口绑定重试次数 */
    private static final int MAX_BIND_RETRIES = 3;
    /** 重试间隔毫秒 */
    private static final long BIND_RETRY_INTERVAL_MS = 1000L;

    private ConfigurableApplicationContext context;
    /** 实际绑定的端口（可能不同于配置值，当端口被占用时 fallback 到随机端口） */
    private int actualPort = -1;

    public BenchServerState(Class<?> applicationClass, Properties defaultProperties) {
        this.applicationClass = applicationClass;
        this.defaultProperties = defaultProperties;
    }

    public void start() {
        int configPort = BenchmarkConstants.PORT;
        System.out.println("[Benchmark] Starting server: " + applicationClass.getSimpleName()
                + " on port " + configPort);

        SpringApplication app = new SpringApplication(applicationClass);
        app.setDefaultProperties(defaultProperties);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);

        // 通过 WebServerInitializedEvent 捕获实际端口（适用于所有容器，不依赖 local.server.port）
        // NettyHttpServer 不设置 local.server.port，必须通过事件获取
        final int[] eventPort = {0};
        app.addListeners((ApplicationListener<WebServerInitializedEvent>) event -> {
            if (eventPort[0] == 0) {
                eventPort[0] = event.getWebServer().getPort();
                System.out.println("[Benchmark] WebServerInitializedEvent port: " + eventPort[0]);
            }
        });

        // 首次启动尝试 + 重试（处理 TIME_WAIT 等短暂端口不可用场景）
        for (int attempt = 1; attempt <= MAX_BIND_RETRIES; attempt++) {
            try {
                context = app.run();
                break; // 成功
            } catch (Exception e) {
                if (attempt < MAX_BIND_RETRIES && isPortBindFailure(e)) {
                    System.out.println("[Benchmark] Port " + configPort
                            + " bind failed (attempt " + attempt + "/" + MAX_BIND_RETRIES
                            + "): " + e.getMessage() + ", retrying in "
                            + BIND_RETRY_INTERVAL_MS + "ms");
                    try {
                        Thread.sleep(BIND_RETRY_INTERVAL_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                // 重试耗尽，使用随机端口 fallback
                System.out.println("[Benchmark] Port " + configPort + " bind failed: "
                        + e.getMessage() + ", trying random port");
                Properties fallbackProps = new Properties();
                fallbackProps.putAll(defaultProperties);
                fallbackProps.setProperty("server.port", "0");
                app.setDefaultProperties(fallbackProps);
                context = app.run();
                break;
            }
        }

        // 确定实际端口：优先使用事件捕获的端口，其次 environment 属性
        if (context != null && context.isRunning()) {
            if (eventPort[0] > 0) {
                actualPort = eventPort[0];
            } else {
                // 兜底：检查 environment 属性
                String portStr = context.getEnvironment().getProperty("server.port");
                if ("0".equals(portStr)) {
                    portStr = context.getEnvironment().getProperty("local.server.port");
                }
                if (portStr != null && !portStr.isEmpty()) {
                    actualPort = Integer.parseInt(portStr);
                } else {
                    actualPort = configPort;
                }
            }
        }
        System.out.println("[Benchmark] Server started: " + applicationClass.getSimpleName()
                + " on port " + actualPort);
    }

    public int getActualPort() {
        return actualPort > 0 ? actualPort : BenchmarkConstants.PORT;
    }

    public void stop() {
        if (context != null) {
            System.out.println("[Benchmark] Stopping server: " + applicationClass.getSimpleName());
            SpringApplication.exit(context, () -> 0);
            context = null;
            // 等待实际绑定的端口释放（不是 BenchmarkConstants.PORT）
            waitForPortRelease(actualPort > 0 ? actualPort : BenchmarkConstants.PORT, 15_000);
            System.out.println("[Benchmark] Server stopped");
        }
    }

    /**
     * 判断异常是否为端口绑定失败（BindException / Address already in use）。
     */
    private static boolean isPortBindFailure(Exception e) {
        String msg = e.getMessage();
        if (msg == null) {
            // 检查 cause chain
            Throwable cause = e.getCause();
            while (cause != null) {
                if (cause.getMessage() != null
                        && (cause.getMessage().contains("Address already in use")
                        || cause.getMessage().contains("bind")
                        || cause.getMessage().contains("EADDRINUSE"))) {
                    return true;
                }
                cause = cause.getCause();
            }
            return false;
        }
        return msg.contains("Address already in use")
                || msg.contains("bind")
                || msg.contains("EADDRINUSE");
    }

    /**
     * 轮询指定端口是否已被释放（不再接受连接），替代固定 sleep。
     *
     * @param port      目标端口
     * @param timeoutMs 最大等待毫秒
     */
    private static void waitForPortRelease(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int pollCount = 0;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket("localhost", port)) {
                // 连接成功 → 端口还被占用
                pollCount++;
                Thread.sleep(200);
            } catch (@SuppressWarnings("java:S2142") InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // 连接被拒绝 → 端口已释放
                if (pollCount > 0) {
                    System.out.println("[Benchmark] Port " + port + " released after "
                            + (pollCount * 200) + "ms polling");
                }
                return;
            }
        }
        System.out.println("[Benchmark] WARN: Port " + port + " still in use after "
                + timeoutMs + "ms, continuing anyway (may cause EADDRINUSE)");
    }
}
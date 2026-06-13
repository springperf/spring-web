package io.springperf.benchmark.common;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.Socket;
import java.util.Properties;

/**
 * 每个 JMH fork 启动一个隔离的 Spring Boot 服务器实例。
 * 由具体 Benchmark 类在 @Setup/@TearDown 中手动调用 start()/stop()。
 * <p>
 * stop() 使用端口可用性轮询而非固定 sleep，确保释放确认后才返回。
 */
public class BenchServerState {

    private final Class<?> applicationClass;
    private final Properties defaultProperties;

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
        try {
            context = app.run();
        } catch (Exception e) {
            // 如果配置的端口被占用，尝试随机端口
            System.out.println("[Benchmark] Port " + configPort + " bind failed: "
                    + e.getMessage() + ", trying random port");
            Properties fallbackProps = new Properties();
            fallbackProps.putAll(defaultProperties);
            fallbackProps.setProperty("server.port", "0");
            app.setDefaultProperties(fallbackProps);
            context = app.run();
        }
        // 获取实际绑定的端口
        if (context != null && context.isRunning()) {
            String portStr = context.getEnvironment().getProperty("server.port");
            if (portStr != null && !portStr.isEmpty()) {
                actualPort = Integer.parseInt(portStr);
            } else {
                actualPort = configPort;
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
            // 轮询等待端口释放，最多 15 秒
            waitForPortRelease(BenchmarkConstants.PORT, 15_000);
            System.out.println("[Benchmark] Server stopped");
        }
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
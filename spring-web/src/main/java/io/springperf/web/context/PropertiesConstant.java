package io.springperf.web.context;

import java.util.HashMap;
import java.util.Map;

/**
 * Constants for property keys used with {@link ApplicationProperties#get(String, String)}
 * and typed variants.
 */
public final class PropertiesConstant {

    private PropertiesConstant() {
    }

    // ========== 属性键与默认值 ==========

    /** Netty server HTTP port. */
    public static final String SERVER_PORT = "server.port";
    public static final int SERVER_PORT_DEFAULT = 8080;

    /** Context path of the application. */
    public static final String CONTEXT_PATH = "server.servlet.context-path";

    /** Max content length of an HTTP request in bytes (默认值：4MB). */
    public static final String HTTP_MAX_CONTENT_LENGTH = "server.http.max-content-length";
    public static final int HTTP_MAX_CONTENT_LENGTH_DEFAULT = 4 * 1024 * 1024;

    /** HTTP request timeout in milliseconds (默认值：60s). */
    public static final String HTTP_TIMEOUT = "server.http.timeout";
    public static final long HTTP_TIMEOUT_DEFAULT = 60000L;

    /** HTTP socket read timeout in milliseconds, before request aggregation (默认值：30s).
     * 用于防止慢速客户端在聚合请求体前无限期占用连接。0 或负数表示关闭读取超时。 */
    public static final String HTTP_READ_TIMEOUT = "server.http.read-timeout";
    public static final long HTTP_READ_TIMEOUT_DEFAULT = 30000L;

    /** Whether to check handler mappings on startup. */
    public static final String CHECK_ON_STARTUP = "server.check-on-startup";

    // ---- 业务线程池 ----

    public static final String POOL_CORE_POOL_SIZE = "pool.core-pool-size";
    public static final int POOL_CORE_POOL_SIZE_DEFAULT = 50;

    public static final String POOL_MAX_POOL_SIZE = "pool.max-pool-size";
    public static final int POOL_MAX_POOL_SIZE_DEFAULT = 200;

    public static final String POOL_KEEP_ALIVE_TIME = "pool.keep-alive-time";
    public static final int POOL_KEEP_ALIVE_TIME_DEFAULT = 60;

    /**
     * 默认业务线程池任务队列容量。必须为有界值：
     * 无界队列下 {@code ThreadPoolExecutor} 的 maxPoolSize 永不生效（线程数封顶在 core），
     * 且队列永不 reject，503 快速失败兜底形同虚设。有界队列让线程池可从 core 扩容到 max，
     * 满员后请求被拒绝并返回 503（见 DispatcherHandler）。
     */
    public static final String POOL_QUEUE_CAPACITY = "pool.queue-capacity";
    public static final int POOL_QUEUE_CAPACITY_DEFAULT = 100;

    /**
     * 无 {@code @RunInPool} 注解时方法的默认执行位置。
     * 值 "default"（默认）表示在 default 业务线程池执行，
     * 值 "eventloop" 表示在 Netty EventLoop 执行，
     * 其他字符串被视为线程池名称。
     */
    public static final String POOL_DEFAULT_EXECUTE_MODE = "pool.default-execute-mode";
    public static final String POOL_DEFAULT_EXECUTE_MODE_DEFAULT = "default";

    // ---- 异步支持 ----

    /** Async request timeout in milliseconds (默认值：30s). */
    public static final String ASYNC_TIMEOUT = "server.async.timeout";
    public static final long ASYNC_TIMEOUT_DEFAULT = 30000L;

    // ---- 优雅关闭 ----

    /** Graceful shutdown max wait time in milliseconds (默认值：30s). */
    public static final String SERVER_SHUTDOWN_TIMEOUT = "server.shutdown.timeout";
    public static final int SERVER_SHUTDOWN_TIMEOUT_DEFAULT = 30000;

    /** Netty worker event loop thread count (默认值：0 表示自动计算). */
    public static final String SERVER_NETTY_WORKERS = "server.netty.workers";
    public static final int SERVER_NETTY_WORKERS_DEFAULT = 0;

    /** Write buffer low watermark in bytes (默认值：8KB). */
    public static final String WRITE_BUFFER_LOW_WATERMARK = "server.netty.write-buffer-low-watermark";
    public static final int WRITE_BUFFER_LOW_WATERMARK_DEFAULT = 8192;

    /** Write buffer high watermark in bytes (默认值：32KB). */
    public static final String WRITE_BUFFER_HIGH_WATERMARK = "server.netty.write-buffer-high-watermark";
    public static final int WRITE_BUFFER_HIGH_WATERMARK_DEFAULT = 32768;

    // ---- 转发头 ----

    /**
     * 是否信任 {@code Forwarded} / {@code X-Forwarded-Proto} 等代理转发头。
     * 默认 {@code false}，仅当应用部署在反向代理（Nginx / ALB 等）后方时应开启。
     * 开启后，框架将根据转发头确定请求的 scheme（http / https）和远程地址。
     */
    public static final String USE_FORWARDED_HEADERS = "server.use-forwarded-headers";

    // ---- HTTP/2 ----

    /** Enable HTTP/2 support (requires SSL for browser clients). */
    public static final String HTTP2_ENABLED = "server.http2.enabled";

    // ---- HTTP 解析器限制 ----

    /** Max length of the HTTP request initial line (URI + method + version) in bytes (default: 4KB). */
    public static final String HTTP_MAX_INITIAL_LINE_LENGTH = "server.http.max-initial-line-length";
    public static final int HTTP_MAX_INITIAL_LINE_LENGTH_DEFAULT = 4096;

    /** Max size of all HTTP request headers combined in bytes (default: 8KB). */
    public static final String HTTP_MAX_HEADER_SIZE = "server.http.max-header-size";
    public static final int HTTP_MAX_HEADER_SIZE_DEFAULT = 8192;

    /** Max size of each HTTP chunk in bytes (default: 8KB). */
    public static final String HTTP_MAX_CHUNK_SIZE = "server.http.max-chunk-size";
    public static final int HTTP_MAX_CHUNK_SIZE_DEFAULT = 8192;

    // ---- Netty ChannelOption ----

    /** Netty boss event loop thread count (默认值：1). */
    public static final String SERVER_NETTY_BOSS_THREADS = "server.netty.boss-threads";
    public static final int SERVER_NETTY_BOSS_THREADS_DEFAULT = 1;

    /** TCP listen backlog (默认值：1024，生产 Linux 高并发). */
    public static final String SERVER_NETTY_SO_BACKLOG = "server.netty.so-backlog";
    public static final int SERVER_NETTY_SO_BACKLOG_DEFAULT = 1024;

    /** TCP_NODELAY, disable Nagle's algorithm (默认值：true). */
    public static final String SERVER_NETTY_TCP_NODELAY = "server.netty.tcp-nodelay";
    public static final boolean SERVER_NETTY_TCP_NODELAY_DEFAULT = true;

    /** SO_KEEPALIVE (默认值：true，生产长连接友好). */
    public static final String SERVER_NETTY_SO_KEEPALIVE = "server.netty.so-keepalive";
    public static final boolean SERVER_NETTY_SO_KEEPALIVE_DEFAULT = true;

    /** SO_REUSEADDR (默认值：true). */
    public static final String SERVER_NETTY_SO_REUSEADDR = "server.netty.so-reuseaddr";
    public static final boolean SERVER_NETTY_SO_REUSEADDR_DEFAULT = true;

    /** ByteBuf allocator type: "pooled" or "unpooled" (默认值：pooled). */
    public static final String SERVER_NETTY_ALLOCATOR_TYPE = "server.netty.allocator-type";
    public static final String SERVER_NETTY_ALLOCATOR_TYPE_DEFAULT = "pooled";

    /**
     * Netty transport type: "auto" / "nio" / "epoll".
     * auto（默认）：Linux 上 epoll 可用时自动使用 native epoll，否则回退 NIO（Windows/macOS）。
     * nio：强制 Java NIO。epoll：强制 native epoll，不可用时启动失败。
     */
    public static final String SERVER_NETTY_TRANSPORT = "server.netty.transport";
    public static final String SERVER_NETTY_TRANSPORT_DEFAULT = "auto";

    /** Max request body bytes kept in-memory before switching to ByteBuf duplicate (默认值：4KB). */
    public static final String HTTP_MAX_IN_MEMORY_SIZE = "server.http.max-in-memory-size";
    public static final int HTTP_MAX_IN_MEMORY_SIZE_DEFAULT = 4096;

    // ---- 访问日志 ----

    /** Enable access log. */
    public static final String ACCESSLOG_ENABLED = "server.accesslog.enabled";

    /**
     * Access log format pattern.
     * Supported tokens: %h (remote addr), %m (method), %U (URI), %T (elapsed ms),
     * %s (status), %u (user-agent), literal text otherwise.
     * Default: "%h %m %U %Tms %s \"%u\""
     */
    public static final String ACCESSLOG_FORMAT = "server.accesslog.format";

    // ========== 默认值查询 ==========

    private static final Map<String, Long> DEFAULTS = new HashMap<>();

    static {
        DEFAULTS.put(SERVER_PORT, (long) SERVER_PORT_DEFAULT);
        DEFAULTS.put(HTTP_MAX_CONTENT_LENGTH, (long) HTTP_MAX_CONTENT_LENGTH_DEFAULT);
        DEFAULTS.put(HTTP_TIMEOUT, HTTP_TIMEOUT_DEFAULT);
        DEFAULTS.put(POOL_CORE_POOL_SIZE, (long) POOL_CORE_POOL_SIZE_DEFAULT);
        DEFAULTS.put(POOL_MAX_POOL_SIZE, (long) POOL_MAX_POOL_SIZE_DEFAULT);
        DEFAULTS.put(POOL_KEEP_ALIVE_TIME, (long) POOL_KEEP_ALIVE_TIME_DEFAULT);
        DEFAULTS.put(POOL_QUEUE_CAPACITY, (long) POOL_QUEUE_CAPACITY_DEFAULT);
        DEFAULTS.put(ASYNC_TIMEOUT, ASYNC_TIMEOUT_DEFAULT);
        DEFAULTS.put(SERVER_SHUTDOWN_TIMEOUT, (long) SERVER_SHUTDOWN_TIMEOUT_DEFAULT);
        DEFAULTS.put(SERVER_NETTY_WORKERS, (long) SERVER_NETTY_WORKERS_DEFAULT);
        DEFAULTS.put(WRITE_BUFFER_LOW_WATERMARK, (long) WRITE_BUFFER_LOW_WATERMARK_DEFAULT);
        DEFAULTS.put(WRITE_BUFFER_HIGH_WATERMARK, (long) WRITE_BUFFER_HIGH_WATERMARK_DEFAULT);
        DEFAULTS.put(HTTP_MAX_INITIAL_LINE_LENGTH, (long) HTTP_MAX_INITIAL_LINE_LENGTH_DEFAULT);
        DEFAULTS.put(HTTP_MAX_HEADER_SIZE, (long) HTTP_MAX_HEADER_SIZE_DEFAULT);
        DEFAULTS.put(HTTP_MAX_CHUNK_SIZE, (long) HTTP_MAX_CHUNK_SIZE_DEFAULT);
        DEFAULTS.put(SERVER_NETTY_BOSS_THREADS, (long) SERVER_NETTY_BOSS_THREADS_DEFAULT);
        DEFAULTS.put(SERVER_NETTY_SO_BACKLOG, (long) SERVER_NETTY_SO_BACKLOG_DEFAULT);
        DEFAULTS.put(HTTP_MAX_IN_MEMORY_SIZE, (long) HTTP_MAX_IN_MEMORY_SIZE_DEFAULT);
    }

    /**
     * 根据 key 返回 long 默认值（int 属性也统一存为 long）。
     * 仅在首次 cache miss 时调用一次，非热点路径。
     */
    public static long getDefault(String key) {
        return DEFAULTS.getOrDefault(key, 0L);
    }
}

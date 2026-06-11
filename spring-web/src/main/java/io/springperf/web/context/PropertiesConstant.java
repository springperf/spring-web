package io.springperf.web.context;

/**
 * Constants for property keys used with {@link ApplicationProperties#get(String, String)}
 * and typed variants.
 */
public final class PropertiesConstant {

    private PropertiesConstant() {
    }

    /** Netty server HTTP port. */
    public static final String SERVER_PORT = "server.port";

    /** Context path of the application. */
    public static final String CONTEXT_PATH = "server.servlet.context-path";

    /** Max content length of an HTTP request in bytes. */
    public static final String HTTP_MAX_CONTENT_LENGTH = "server.http.max-content-length";

    /** HTTP request timeout in milliseconds. */
    public static final String HTTP_TIMEOUT = "server.http.timeout";

    /** Whether to check handler mappings on startup. */
    public static final String CHECK_ON_STARTUP = "server.check-on-startup";

    // ---- 业务线程池 ----

    /** Default biz pool core pool size. */
    public static final String POOL_CORE_POOL_SIZE = "pool.core-pool-size";

    /** Default biz pool max pool size. */
    public static final String POOL_MAX_POOL_SIZE = "pool.max-pool-size";

    /** Biz pool thread keep-alive time in seconds. */
    public static final String POOL_KEEP_ALIVE_TIME = "pool.keep-alive-time";

    /** Biz pool queue capacity (unbounded by default). */
    public static final String POOL_QUEUE_CAPACITY = "pool.queue-capacity";

    // ---- 异步支持 ----

    /** Async request timeout in milliseconds. */
    public static final String ASYNC_TIMEOUT = "server.async.timeout";

    // ---- 优雅关闭 ----

    /** Graceful shutdown max wait time in milliseconds. */
    public static final String SERVER_SHUTDOWN_TIMEOUT = "server.shutdown.timeout";

    /** Netty worker event loop thread count (default: 2 * CPU cores). */
    public static final String SERVER_NETTY_WORKERS = "server.netty.workers";
}

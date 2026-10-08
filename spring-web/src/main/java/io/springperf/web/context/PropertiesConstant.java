package io.springperf.web.context;

import java.util.HashMap;
import java.util.Map;

/**
 * Constants for property keys used with {@link ApplicationProperties#get(String, String)} and typed variants.
 */
public final class PropertiesConstant {

    private PropertiesConstant() {
    }

    // ========== 属性键与默认值 ==========

    /** Netty server HTTP port. */
    public static final String SERVER_PORT = "server.port";
    public static final int SERVER_PORT_DEFAULT = 8080;

    /**
     * Bind address (network interface / IP). Null/empty = bind all interfaces (0.0.0.0). 对齐 Spring Boot
     * {@code server.address}。
     */
    public static final String SERVER_ADDRESS = "server.address";

    /** Context path of the application. */
    public static final String CONTEXT_PATH = "server.servlet.context-path";

    /** Max content length of an HTTP request in bytes (默认值：4MB). */
    public static final String HTTP_MAX_CONTENT_LENGTH = "server.http.max-content-length";
    public static final int HTTP_MAX_CONTENT_LENGTH_DEFAULT = 4 * 1024 * 1024;

    /** HTTP request timeout in milliseconds (默认值：60s). */
    /**
     * 响应超时：单请求从进入管线到响应提交的时限（毫秒；{@code <=0} 表示不限制）。 超时写出 504 Gateway Timeout；响应一旦提交（flush/流式首帧）即取消计时。
     */
    public static final String HTTP_TIMEOUT = "server.http.timeout";
    public static final long HTTP_TIMEOUT_DEFAULT = 60000L;

    /**
     * HTTP socket read **idle** timeout in milliseconds, before request aggregation（**默认 0 = 关闭**）。
     * 启用后用于防止慢速客户端在聚合请求体前无限期占用连接； 只回收**真正空闲**的连接与半截请求，在途请求不会被掐断（见 {@code ReadIdleTimeoutHandler}）。0 或负数表示关闭。
     * <p>
     * <b>默认值与 Boot/Tomcat 不同</b>（Tomcat {@code connectionTimeout} 默认 20s）：本项目默认**不施加连接级隐式限制**，需要读空闲防护的部署请显式设置
     * <b>（建议生产环境设置：不设则慢速客户端可长期占用连接）</b>。
     * </p>
     * <p>
     * 与历史的一次修复不要混淆：本键曾因漏进 {@code DEFAULTS} 而"文档写 30s、实际关闭"，当时按**缺陷**修复；现在的默认关闭是**有意为之的策略选择**。
     * </p>
     */
    public static final String HTTP_READ_TIMEOUT = "server.http.read-timeout";
    public static final long HTTP_READ_TIMEOUT_DEFAULT = 0L;

    /**
     * 多段 Range（{@code multipart/byteranges}）允许的最大段数，默认 {@value #HTTP_MAX_RANGES_DEFAULT} （与底层解析器上限一致，即默认不额外收紧）。
     * <p>
     * <b>有效上限 = min(本值, 100)</b>：Range 头由 Spring {@code HttpRange} 解析，其对超过 100 段 直接抛
     * {@code IllegalArgumentException: Too many ranges}，框架按「Range 不可用」忽略该头。 因此本键的实际用途是**把上限收紧到小于 100**（例如只允许 2 段）；设成大于
     * 100 不会放宽。
     * </p>
     * <p>
     * 超过有效上限时按 RFC 9110 §14.2「服务器可忽略 Range」返回**整实体 200**——既不截断段数 （截断会让客户端拿到与请求不符的表示），也不回 416（请求本身合法）。防护意义：每个段都会打开
     * 一路区间输入流 + 段头缓冲，只有单段/少量段需求的静态资源可据此收紧。
     * </p>
     * <p>
     * {@code 0} 表示禁止多段（任何多段请求都回退整实体）；负值表示本层不额外限制（仍受 100 段固有限制）。
     * </p>
     * <p>
     * 命名说明：与 {@code server.http.multipart.max-part-count} 同族——框架自有的管线防护型键 归入 {@code server.http.*}，不借用
     * {@code server.tomcat.*} 前缀。
     * </p>
     */
    public static final String HTTP_MAX_RANGES = "server.http.max-ranges";
    public static final int HTTP_MAX_RANGES_DEFAULT = 100;

    /**
     * 单连接上允许**排队等待**的同连接请求数上限（HTTP/1.1 pipelining / 同段多请求）， 默认
     * {@value #HTTP_MAX_PIPELINED_REQUESTS_DEFAULT}；{@code <=0} 表示不限制。
     * <p>
     * 框架把同连接请求串行化（响应保序），排队项各自持有一份已聚合请求（body 上限 {@code server.http.max-content-length}，默认 4MB）。若不设限，客户端仅凭单连接发送大量
     * pipelined 请求即可放大内存占用。
     * </p>
     * <p>
     * 达到上限时的处理是**暂停读取**（{@code autoRead=false}）而非关闭连接或拒绝请求： pipelining 要求响应保序，无法只拒绝靠后的请求；暂停后未读字节留在 socket 缓冲， 由 TCP
     * 窗口对客户端形成背压，待队列排空自动恢复读取。
     * </p>
     */
    public static final String HTTP_MAX_PIPELINED_REQUESTS = "server.http.max-pipelined-requests";
    public static final int HTTP_MAX_PIPELINED_REQUESTS_DEFAULT = 16;

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
     * 默认业务线程池任务队列容量。必须为有界值： 无界队列下 {@code ThreadPoolExecutor} 的 maxPoolSize 永不生效（线程数封顶在 core）， 且队列永不 reject，503
     * 快速失败兜底形同虚设。有界队列让线程池可从 core 扩容到 max， 满员后请求被拒绝并返回 503（见 DispatcherHandler）。
     */
    public static final String POOL_QUEUE_CAPACITY = "pool.queue-capacity";
    public static final int POOL_QUEUE_CAPACITY_DEFAULT = 100;

    /**
     * 无 {@code @RunInPool} 注解时方法的默认执行位置。 值 "default"（默认）表示在 default 业务线程池执行， 值 "eventloop" 表示在 Netty EventLoop 执行，
     * 其他字符串被视为线程池名称。
     */
    public static final String POOL_DEFAULT_EXECUTE_MODE = "pool.default-execute-mode";
    public static final String POOL_DEFAULT_EXECUTE_MODE_DEFAULT = "default";

    // ---- 异步支持 ----

    /**
     * 对齐 Spring Boot {@code spring.mvc.async.request-timeout}：异步请求超时（Duration，默认 30s）。 支持
     * {@code 30s}/{@code 1m}/{@code 1h}，纯数字按毫秒解析。
     */
    public static final String ASYNC_REQUEST_TIMEOUT = "spring.mvc.async.request-timeout";
    public static final long ASYNC_REQUEST_TIMEOUT_DEFAULT = 30000L;

    // ---- 优雅关闭 ----

    /**
     * 对齐 Spring Boot 3.x {@code server.shutdown.grace-period}：优雅关闭等待时长（Duration，默认 30s）。 支持
     * {@code 30s}/{@code 1m}/{@code 1h}，纯数字按毫秒解析。
     */
    public static final String SERVER_SHUTDOWN_GRACE_PERIOD = "server.shutdown.grace-period";
    public static final long SERVER_SHUTDOWN_GRACE_PERIOD_DEFAULT = 30000L;

    // ---- 连接与参数限制 ----

    /**
     * 对齐 Spring Boot {@code server.max-connections}：单服务器最大并发 TCP 连接数（≤0 表示不限制，默认 0）。 超限时直接关闭 TCP 连接，对齐 Tomcat accept
     * 队列满的拒绝语义。
     */
    public static final String SERVER_MAX_CONNECTIONS = "server.max-connections";
    public static final int SERVER_MAX_CONNECTIONS_DEFAULT = 0;

    /**
     * 对齐 Spring Boot / Tomcat {@code server.max-parameter-count}：请求参数值总数上限（≤0 表示不限制，默认 10000）。 覆盖 query +
     * form-urlencoded + multipart attribute 的参数值总个数（hash DoS 防护）。
     */
    public static final String SERVER_MAX_PARAMETER_COUNT = "server.max-parameter-count";
    public static final int SERVER_MAX_PARAMETER_COUNT_DEFAULT = 10000;

    /**
     * multipart 请求 part 总数上限（≤0 表示不限制，默认 -1，语义对齐 Spring Boot {@code server.tomcat.max-part-count}）。 覆盖 file + attribute
     * 的 part 数量（hash DoS 防护）。
     * <p>
     * 命名说明：本框架非 Tomcat，不借用 {@code server.tomcat.*} 前缀；该键属 HTTP 协议层上限， 归入 {@code server.http.*} 家族（与 max-chunk-size /
     * max-in-memory-size 同族）。
     * </p>
     */
    public static final String HTTP_MULTIPART_MAX_PART_COUNT = "server.http.multipart.max-part-count";

    /**
     * 对齐 Spring Boot {@code spring.servlet.multipart.max-file-size}：单个上传文件大小上限 （Duration 风格字节串，如
     * {@code 10MB}/{@code 1GB}；{@code -1} 表示不限制）。
     * <p>
     * 超限时 multipart 解析抛异常 → 由 {@code SupportMultipartAggregator} 转 413。
     * </p>
     */
    public static final String SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE = "spring.servlet.multipart.max-file-size";
    public static final String SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE_DEFAULT = "-1";

    /**
     * 对齐 Spring Boot {@code spring.servlet.multipart.max-request-size}：整个 multipart 请求体 大小上限（Duration 风格字节串，如
     * {@code 10MB}；{@code -1} 表示不限制）。
     * <p>
     * 与 {@code server.http.max-content-length} 同为整体上限语义；本键在 multipart 场景优先， 未配置时回退
     * {@code server.http.max-content-length}。
     * </p>
     */
    public static final String SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE = "spring.servlet.multipart.max-request-size";
    public static final String SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE_DEFAULT = "-1";

    /**
     * 对齐 Spring Boot {@code spring.servlet.multipart.enabled}：是否启用 multipart 解析（默认 true）。 false 时上传请求按普通请求处理（不解析为
     * MultipartFile）。
     */
    public static final String SPRING_SERVLET_MULTIPART_ENABLED = "spring.servlet.multipart.enabled";
    public static final boolean SPRING_SERVLET_MULTIPART_ENABLED_DEFAULT = true;

    /**
     * 对齐 Spring Boot {@code spring.servlet.multipart.file-size-threshold}：part 写入磁盘的阈值 （DataSize 风格，如
     * {@code 1MB}）。小于该值的 part 保留在内存，超过则落临时文件。
     * <p>
     * 默认 -1：沿用框架既有行为（Netty {@code MINSIZE}=16KB，即超过 16KB 落盘）。用户可显式 设为 {@code 0} 对齐 Boot 的「全部落盘」保守语义，或调大以减少小文件的无谓磁盘 IO。
     * </p>
     */
    public static final String SPRING_SERVLET_MULTIPART_FILE_SIZE_THRESHOLD = "spring.servlet.multipart.file-size-threshold";
    public static final String SPRING_SERVLET_MULTIPART_FILE_SIZE_THRESHOLD_DEFAULT = "-1";

    /**
     * 对齐 Spring Boot {@code spring.servlet.multipart.location}：上传临时文件存放目录。 默认空：沿用 Netty 默认（系统临时目录
     * {@code java.io.tmpdir}）。
     * <p>
     * 容器化部署（tmpfs 受限/内存盘）时建议显式指向数据盘，避免写入失败或挤占内存。
     * </p>
     */
    public static final String SPRING_SERVLET_MULTIPART_LOCATION = "spring.servlet.multipart.location";
    public static final String SPRING_SERVLET_MULTIPART_LOCATION_DEFAULT = "";
    public static final int HTTP_MULTIPART_MAX_PART_COUNT_DEFAULT = -1;

    /**
     * 单个 multipart part 的 header 区字节上限（≤0 表示不限制）。防恶意超长 part header 耗尽内存。 注：Netty {@code HttpPostRequestDecoder} 无单 part
     * header 原生限制，由框架在消费流时增量扫描实现（B2）。
     * <p>
     * <b>默认值与 Boot 不同</b>：本项目为 {@code 8192}，而 Boot 元数据里 {@code server.tomcat.max-part-header-size} 的默认值是
     * {@code 512B}（相差 16 倍）。此处是**本项目的取值选择**，并非对齐 Boot/Tomcat；写法上刻意把两者都写出来， 便于读者区分「设计选择」与「对齐」。
     * </p>
     * <p>
     * 键名语义与 Boot 的同名键一致（都是「单个 part header 的字节上限，≤0 不限」），超限由增量扫描抛 {@code DecoderException}，由聚合器转为 HTTP {@code 400}。
     * </p>
     * <p>
     * 命名说明：归入 {@code server.http.multipart.*}（见上）。
     * </p>
     */
    public static final String HTTP_MULTIPART_MAX_PART_HEADER_SIZE = "server.http.multipart.max-part-header-size";
    public static final int HTTP_MULTIPART_MAX_PART_HEADER_SIZE_DEFAULT = 8192;

    // ---- 响应压缩（对齐 server.compression.*）----

    /**
     * 对齐 Spring Boot {@code server.compression.enabled}：响应 gzip 压缩总开关（默认 false）。
     */
    public static final String SERVER_COMPRESSION_ENABLED = "server.compression.enabled";
    public static final boolean SERVER_COMPRESSION_ENABLED_DEFAULT = false;

    /**
     * 对齐 Spring Boot {@code server.compression.mime-types}：仅这些 Content-Type（主类型）参与压缩的逗号分隔白名单。
     */
    public static final String SERVER_COMPRESSION_MIME_TYPES = "server.compression.mime-types";
    public static final String SERVER_COMPRESSION_MIME_TYPES_DEFAULT = "text/html,text/xml,text/plain,text/css,text/javascript,application/javascript,application/json,application/xml";

    /**
     * 对齐 Spring Boot {@code server.compression.excluded-user-agents}：命中则跳过压缩的 User-Agent 正则列表（逗号分隔）。
     */
    public static final String SERVER_COMPRESSION_EXCLUDED_USER_AGENTS = "server.compression.excluded-user-agents";

    /**
     * 对齐 Spring Boot {@code server.compression.min-response-size}：响应体小于该大小（DataSize，默认 2KB）不压缩。
     */
    public static final String SERVER_COMPRESSION_MIN_RESPONSE_SIZE = "server.compression.min-response-size";
    public static final String SERVER_COMPRESSION_MIN_RESPONSE_SIZE_DEFAULT = "2KB";

    /** 响应压缩 gzip 级别（Spring 规范未暴露，固定 6，对齐 Netty 默认）。 */
    public static final int SERVER_COMPRESSION_LEVEL_DEFAULT = 6;

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
     * 转发头处理策略，对齐 Spring Boot {@code server.forward-headers-strategy}： NONE（默认，不信任）/ FRAMEWORK（框架解析 Forwarded /
     * X-Forwarded-*）/ NATIVE（本框架无原生容器，与 FRAMEWORK 行为一致）。
     */
    public static final String FORWARD_HEADERS_STRATEGY = "server.forward-headers-strategy";
    public static final String FORWARD_HEADERS_STRATEGY_DEFAULT = "NONE";

    /**
     * 对齐 Spring Boot {@code spring.mvc.throw-exception-if-no-handler-found}： 404/405 时是否抛出 ResponseStatusException 交由
     * ExceptionRegistry（@ControllerAdvice）处理。 默认 true 以保持框架既有行为（总是进入异常解析）；设为 false 则直接 sendError 不进异常解析。
     */
    public static final String THROW_EXCEPTION_IF_NO_HANDLER_FOUND = "spring.mvc.throw-exception-if-no-handler-found";
    public static final boolean THROW_EXCEPTION_IF_NO_HANDLER_FOUND_DEFAULT = true;

    /**
     * 对齐 Spring Boot {@code spring.mvc.dispatch.error}：是否把 ERROR 方法请求分发给处理器（默认 true）。 false 时 DispatcherServlet 不处理
     * ERROR dispatch（对齐 Boot，错误页由容器/框架错误处理接管）。
     */
    public static final String MVC_DISPATCH_ERROR = "spring.mvc.dispatch.error";
    public static final boolean MVC_DISPATCH_ERROR_DEFAULT = true;
    /**
     * 对齐 Spring Boot {@code spring.mvc.dispatch.options}：是否把 OPTIONS 请求分发给处理器（默认 true）。 false 时 OPTIONS
     * 请求不进入 @RequestMapping 匹配（框架仍可回退 CORS 预检处理）。
     */
    public static final String MVC_DISPATCH_OPTIONS = "spring.mvc.dispatch.options";
    public static final boolean MVC_DISPATCH_OPTIONS_DEFAULT = true;
    /**
     * 对齐 Spring Boot {@code spring.mvc.dispatch.trace}：是否把 TRACE 请求分发给处理器（默认 true）。 false 时 TRACE 请求不进入 @RequestMapping
     * 匹配（通常出于安全考虑关闭）。
     */
    public static final String MVC_DISPATCH_TRACE = "spring.mvc.dispatch.trace";
    public static final boolean MVC_DISPATCH_TRACE_DEFAULT = true;

    // ---- Servlet / MVC / Error / Static (对齐 spring.servlet.* 与 spring.mvc.*) ----

    /** 对齐 {@code server.servlet.application-display-name}。 */
    public static final String SERVLET_APPLICATION_DISPLAY_NAME = "server.servlet.application-display-name";

    /** 对齐 {@code server.servlet.encoding.charset}：请求/响应统一字符集（旧键 request/response 两键保留为别名）。 */
    public static final String SERVLET_ENCODING_CHARSET = "server.servlet.encoding.charset";
    public static final String SERVLET_ENCODING_CHARSET_DEFAULT = "UTF-8";

    /**
     * 对齐 {@code server.servlet.encoding.force}：是否强制 charset（同时作用于请求与响应）。 为 true 时，即使业务已显式设置 request/response 编码，仍以配置的
     * charset 覆盖。
     */
    public static final String SERVLET_ENCODING_FORCE = "server.servlet.encoding.force";
    public static final boolean SERVLET_ENCODING_FORCE_DEFAULT = false;

    /**
     * 对齐 {@code server.servlet.encoding.force-request}：是否强制请求 charset（默认继承 {@code force}）。
     */
    public static final String SERVLET_ENCODING_FORCE_REQUEST = "server.servlet.encoding.force-request";

    /**
     * 对齐 {@code server.servlet.encoding.force-response}：是否强制响应 charset（默认继承 {@code force}）。
     */
    public static final String SERVLET_ENCODING_FORCE_RESPONSE = "server.servlet.encoding.force-response";

    /** 对齐 {@code server.servlet.session.tracking-modes}：逗号分隔的 COOKIE/URL/SSL（默认 COOKIE）。 */
    public static final String SERVLET_SESSION_TRACKING_MODES = "server.servlet.session.tracking-modes";

    /**
     * 对齐 {@code server.servlet.context-parameters.*}：ServletContext 初始化参数显式块。 前缀下的每个键（如
     * {@code server.servlet.context-parameters.foo=bar}）作为 init parameter {@code foo=bar} 暴露给
     * {@code ServletContext.getInitParameter}。
     */
    public static final String SERVLET_CONTEXT_PARAMETERS_PREFIX = "server.servlet.context-parameters.";

    /**
     * 对齐 {@code server.servlet.virtual-server-name}：ServletContext 虚拟服务器名（默认 localhost）。 原键 {@code server.virtual-host}
     * 已改名，不留旧键。
     */
    public static final String SERVLET_VIRTUAL_SERVER_NAME = "server.servlet.virtual-server-name";
    public static final String SERVLET_VIRTUAL_SERVER_NAME_DEFAULT = "localhost";

    /**
     * 对齐 {@code server.servlet.session.persistent}：是否将会话持久化到磁盘（默认 false）。 开启后使用 {@code FileHttpSessionStorage}（JDK
     * 序列化，每 session 一文件），重启可恢复未过期会话。
     */
    public static final String SERVLET_SESSION_PERSISTENT = "server.servlet.session.persistent";
    public static final boolean SERVLET_SESSION_PERSISTENT_DEFAULT = false;

    /**
     * 对齐 {@code server.servlet.session.store-dir}：会话持久化目录（默认 {@code .perf-sessions}，相对工作目录）。 仅在
     * {@code server.servlet.session.persistent=true} 时生效。
     */
    public static final String SERVLET_SESSION_STORE_DIR = "server.servlet.session.store-dir";
    public static final String SERVLET_SESSION_STORE_DIR_DEFAULT = ".perf-sessions";

    /**
     * 会话持久化时的属性排除名单（逗号分隔的属性名）：命中者不写入磁盘。 与「不可序列化属性自动跳过」互补，用于显式排除体积大或敏感属性。
     */
    public static final String SERVLET_SESSION_PERSISTENT_EXCLUDE = "server.servlet.session.persistent-exclude";

    /**
     * 会话持久化的**反序列化类过滤器**规格（{@link java.io.ObjectInputFilter.Config#createFilter(String)} 语法， 分号分隔，例如
     * {@code java.util.*;io.springperf.web.*;!*}）。 空/未配置 = **不加过滤器**，与历史行为一致； 配置后只放行规格内的类，其余一律拒绝（拒绝的文件走既有"坏文件容错"路径：warn
     * + 丢弃）。仅在 {@code server.servlet.session.persistent=true} 时生效。
     * <p>
     * <b>本项目自有键，Boot 无对应物</b>（依据：对齐版本 {@code spring-boot-autoconfigure-3.5.16.jar} 的
     * {@code spring-configuration-metadata.json} 中 {@code server.servlet.session.*} 共 13 个键：
     * {@code cookie.name/same-site/secure/domain/max-age/http-only/comment/path/partitioned}、{@code persistent}、
     * {@code store-dir}、{@code timeout}、{@code tracking-modes}；同版本元数据里含 filter/serial 的键均与反序列化无关）。
     * 另注：{@code server.servlet.session.persistent} 与 {@code store-dir} 是 Boot 的 {@code SessionProperties}
     * 在**内嵌容器**里的键，本项目在这里沿用同名键表达自己的落盘实现 —— 键名沿用，实现不同。
     * </p>
     */
    public static final String SERVLET_SESSION_PERSISTENT_DESERIALIZATION_FILTER = "server.servlet.session.persistent-deserialization-filter";

    /** 对齐 {@code spring.mvc.static-path-pattern}：全局静态资源映射前缀（默认 /**）。 */
    public static final String MVC_STATIC_PATH_PATTERN = "spring.mvc.static-path-pattern";
    public static final String MVC_STATIC_PATH_PATTERN_DEFAULT = "/**";

    /**
     * 对齐 {@code spring.web.resources.static-locations}：默认静态资源位置（逗号分隔）。 默认对齐
     * Boot：{@code classpath:/META-INF/resources/,classpath:/resources/,classpath:/static/,classpath:/public/}。
     * 仅用于框架自动注册的默认资源映射（用户显式注册 {@code ResourceHandlerRegistration} 时不受影响）。
     */
    public static final String WEB_RESOURCES_STATIC_LOCATIONS = "spring.web.resources.static-locations";
    public static final String WEB_RESOURCES_STATIC_LOCATIONS_DEFAULT = "classpath:/META-INF/resources/,classpath:/resources/,classpath:/static/,classpath:/public/";

    /**
     * 对齐 {@code spring.web.resources.cache.period}：静态资源缓存时长（Duration 或秒数，如 {@code 1h}）。 未配置（默认）时不设置 Cache-Control。
     */
    public static final String WEB_RESOURCES_CACHE_PERIOD = "spring.web.resources.cache.period";

    /**
     * 对齐 {@code spring.web.resources.cache.cachecontrol.max-age}：Cache-Control max-age （Duration 或秒数）。配置后优先于
     * {@code cache.period}。
     */
    public static final String WEB_RESOURCES_CACHE_CONTROL_MAX_AGE = "spring.web.resources.cache.cachecontrol.max-age";

    /**
     * 是否启用框架内置的默认静态资源映射（{@code static-path-pattern} → {@code static-locations}）。
     * <p>
     * 语义对齐 Boot {@code spring.web.resources.add-mappings}，但**默认 false**——本框架历史行为是 「静态资源完全由用户代码注册」，默认注册 {@code /**}
     * 会接管所有未匹配 GET 请求（改变 404 与异常 解析链路）。用户显式开启后可获得 Boot 式的零配置静态资源服务。
     * </p>
     * <p>
     * 与用户显式注册的 handler **共存**（对齐 Boot）：两者都进路由表，按路径特异性匹配， 用户 pattern（如 {@code /res/**}）优先于默认
     * {@code /**}；{@code cache.period} 只作用于 默认注册（用户注册自带缓存配置）。
     * </p>
     */
    public static final String WEB_RESOURCES_ADD_MAPPINGS = "spring.web.resources.add-mappings";
    public static final boolean WEB_RESOURCES_ADD_MAPPINGS_DEFAULT = false;

    /** 对齐 {@code spring.mvc.format.date} / {@code .time} / {@code .datetime}：日期时间默认格式（Boot 风格，如 yyyy-MM-dd）。 */
    public static final String MVC_FORMAT_DATE = "spring.mvc.format.date";
    public static final String MVC_FORMAT_TIME = "spring.mvc.format.time";
    public static final String MVC_FORMAT_DATETIME = "spring.mvc.format.datetime";
    /** Boot 默认使用 ISO 风格：date=yyyy-MM-dd、time=HH:mm:ss、datetime=yyyy-MM-dd'T'HH:mm:ss。 */
    public static final String MVC_FORMAT_DATE_DEFAULT = "yyyy-MM-dd";
    public static final String MVC_FORMAT_TIME_DEFAULT = "HH:mm:ss";
    public static final String MVC_FORMAT_DATETIME_DEFAULT = "yyyy-MM-dd'T'HH:mm:ss";

    /** 对齐 {@code spring.web.locale}：默认 Locale（如 zh_CN），未配置时按请求 Accept-Language 解析。 */
    public static final String WEB_LOCALE = "spring.web.locale";
    /**
     * 对齐 {@code spring.web.locale-resolver}：Locale 解析策略（fixed / accept-header，默认 accept-header）。 fixed 时恒用
     * {@code spring.web.locale}（未配置则 JVM 默认）；accept-header 时按请求头解析。
     */
    public static final String WEB_LOCALE_RESOLVER = "spring.web.locale-resolver";
    public static final String WEB_LOCALE_RESOLVER_DEFAULT = "accept-header";
    /**
     * 是否每请求绑定 {@code LocaleContextHolder}（默认 true，对齐 Spring MVC）。
     * <p>
     * 设为 false 时框架跳过 Locale 上下文的**设置**：省掉每请求的上下文对象分配与 ThreadLocal set（native 模式下这就是唯一的 holder 工作； servlet 路径下请求结束仍有一次
     * remove 兜底）。此时 {@code LocaleContextHolder.getLocaleContext()} 返回 null， {@code LocaleContextHolder.getLocale()} 按
     * Spring 语义回退 {@code Locale.getDefault()}。 不关注 Locale 的应用（多数 API 服务）可关闭。
     * </p>
     * <p>
     * 注意：关闭后框架**不设置**该 holder（{@code DispatcherHandler.initContextHolders} 早退）。是否**清理**则取决于
     * {@code initContext}（清理调用点受它保护，见 {@code DispatcherHandler.handleAfterFilter}），**两条路径不同**：
     * </p>
     * <ul>
     * <li>native 路径：早退使 {@code initContext=false}，框架既不设置也不清理 —— 应用若自行设置，需自行清理（线程池复用场景）；</li>
     * <li>{@code spring-web-servlet} 路径：{@code SupportDispatcherHandler} 因为还要装 {@code RequestContextHolder}， 覆写后的返回值为
     * {@code init || requestAttributes != null}（恒 true），于是每请求结束时仍会 {@code LocaleContextHolder.resetLocaleContext()} 一次
     * —— 此时应用自行设置的值也会被清掉。</li>
     * </ul>
     */
    public static final String WEB_LOCALE_BIND = "spring.web.locale-bind";
    public static final boolean WEB_LOCALE_BIND_DEFAULT = true;

    /**
     * 对齐 Spring Boot {@code spring.mvc.view.prefix}：JSP 视图前缀。
     * <p>
     * 仅作用于 JSP 视图（对齐 Boot 的 InternalResourceViewResolver 原语义）； 模板引擎（thymeleaf/freemarker/beetl）使用各自的
     * {@code spring.{engine}.prefix}。
     * </p>
     * <p>
     * 默认 {@code /jsp/}：沿用框架既有默认，配置为空串时亦回退该默认。
     * </p>
     */
    public static final String MVC_VIEW_PREFIX = "spring.mvc.view.prefix";
    public static final String MVC_VIEW_PREFIX_DEFAULT = "/jsp/";

    /**
     * 对齐 Spring Boot {@code spring.mvc.view.suffix}：JSP 视图后缀。 默认 {@code .jsp}：沿用框架既有默认，配置为空串时亦回退该默认。
     */
    public static final String MVC_VIEW_SUFFIX = "spring.mvc.view.suffix";
    public static final String MVC_VIEW_SUFFIX_DEFAULT = ".jsp";

    /** 对齐 {@code server.error.include-stacktrace}：never / on-param / always（默认 never）。 */
    public static final String ERROR_INCLUDE_STACKTRACE = "server.error.include-stacktrace";
    public static final String ERROR_INCLUDE_STACKTRACE_DEFAULT = "never";
    /** 对齐 {@code server.error.include-message}：never / always / on-param（默认 never）。 */
    public static final String ERROR_INCLUDE_MESSAGE = "server.error.include-message";
    public static final String ERROR_INCLUDE_MESSAGE_DEFAULT = "never";
    /** 对齐 {@code server.error.include-binding-errors}：never / always（默认 never）。 */
    public static final String ERROR_INCLUDE_BINDING_ERRORS = "server.error.include-binding-errors";
    public static final String ERROR_INCLUDE_BINDING_ERRORS_DEFAULT = "never";
    /** 对齐 {@code server.error.whitelabel.enabled}：是否启用内置错误页（默认 true）。 */
    public static final String ERROR_WHITELABEL_ENABLED = "server.error.whitelabel.enabled";
    public static final boolean ERROR_WHITELABEL_ENABLED_DEFAULT = true;
    /** 对齐 {@code server.error.path}：错误页路径（默认 /error）。 */
    public static final String ERROR_PATH = "server.error.path";
    public static final String ERROR_PATH_DEFAULT = "/error";
    /**
     * 对齐 Spring Boot {@code spring.mvc.problemdetails.enabled}：错误响应改用 RFC 7807 {@code application/problem+json}（默认
     * false，关闭时沿用 whitelabel/JSON）。
     */
    public static final String MVC_PROBLEM_DETAILS_ENABLED = "spring.mvc.problemdetails.enabled";
    public static final boolean MVC_PROBLEM_DETAILS_ENABLED_DEFAULT = false;
    /**
     * 对齐 Spring Boot {@code spring.mvc.message-codes-resolver-format}：校验错误消息码格式。 {@code prefix_error_code}（默认，如
     * {@code NotBlank.target.name}）或 {@code postfix_error_code}（如 {@code target.name.NotBlank}）。
     */
    public static final String MVC_MESSAGE_CODES_RESOLVER_FORMAT = "spring.mvc.message-codes-resolver-format";
    public static final String MVC_MESSAGE_CODES_RESOLVER_FORMAT_DEFAULT = "prefix_error_code";
    /**
     * 请求处理完成后是否发布 {@code ServletRequestHandledEvent}。
     * <p>
     * <b>本项目有意</b>默认 {@code false}（注意：Spring Boot 对该属性的默认值是 {@code true}， 此处并非对齐
     * Boot，而是本框架的性能取向）：该事件对多数应用没有实际用途，而发布是每请求一次 的开销，无监听方时纯属浪费，因此改为「需要监控/审计时显式开启」。
     * </p>
     */
    public static final String MVC_PUBLISH_REQUEST_HANDLED_EVENTS = "spring.mvc.publish-request-handled-events";
    public static final boolean MVC_PUBLISH_REQUEST_HANDLED_EVENTS_DEFAULT = false;

    // ---- HTTP/2 ----

    /** Enable HTTP/2 support (requires SSL for browser clients). */
    public static final String HTTP2_ENABLED = "server.http2.enabled";

    // ---- HTTP 解析器限制 ----

    /** Max length of the HTTP request initial line (URI + method + version) in bytes (default: 4KB). */
    public static final String HTTP_MAX_INITIAL_LINE_LENGTH = "server.http.max-initial-line-length";
    public static final int HTTP_MAX_INITIAL_LINE_LENGTH_DEFAULT = 4096;

    /** Max size of each HTTP chunk in bytes (default: 8KB). */
    public static final String HTTP_MAX_CHUNK_SIZE = "server.http.max-chunk-size";
    public static final int HTTP_MAX_CHUNK_SIZE_DEFAULT = 8192;

    /**
     * 对齐 Spring Boot {@code server.max-http-request-header-size}：合并请求头大小上限（默认 8KB）。
     */
    public static final String HTTP_MAX_REQUEST_HEADER_SIZE = "server.max-http-request-header-size";
    public static final int HTTP_MAX_REQUEST_HEADER_SIZE_DEFAULT = 8192;

    /**
     * 对齐 Spring Boot {@code server.keep-alive-timeout}：keep-alive 连接空闲超时（毫秒，默认 0 表示禁用显式超时，沿用 TCP SO_KEEPALIVE）。 实现见
     * {@code KeepAliveConfig} / {@code KeepAliveHandler}。
     */
    public static final String KEEP_ALIVE_TIMEOUT = "server.keep-alive-timeout";
    public static final long KEEP_ALIVE_TIMEOUT_DEFAULT = 0L;

    /**
     * 键名对齐 Spring Boot {@code server.max-keep-alive-requests}：单 keep-alive 连接最大请求数（**默认 0 = 不限制**；小于等于 0 表示不限）。 实现见
     * {@code KeepAliveConfig} / {@code KeepAliveHandler}。
     * <p>
     * <b>默认值与 Boot/Tomcat 不同</b>（两者默认 100）：本项目默认**不施加连接级隐式限制**，需要上限的部署请显式设置。
     * </p>
     * <p>
     * <b>注意"默认关闭"不带来性能收益</b>：该 handler 的 self time 实测为 0（只做 {@code instanceof} + null 检查 + 自增 + 比较），故这里的理由是"不做隐式限制"，
     * 不是"更快"。本键与 {@link #KEEP_ALIVE_TIMEOUT} 都为 0 时，{@code KeepAliveHandler} **不注入管线**。
     * </p>
     */
    public static final String MAX_KEEP_ALIVE_REQUESTS = "server.max-keep-alive-requests";
    public static final int MAX_KEEP_ALIVE_REQUESTS_DEFAULT = 0;

    /**
     * 对齐 Spring Boot {@code server.max-swallow-size}：错误响应后吞掉请求 body 的上限（字节，默认 2MB；负数不限）。 实现见
     * {@code ResponseLimitConfig} + {@code NettyServerHttpResponse}（P1-4.9）。
     */
    public static final String MAX_SWALLOW_SIZE = "server.max-swallow-size";
    public static final long MAX_SWALLOW_SIZE_DEFAULT = 2 * 1024 * 1024;

    /**
     * 对齐 Spring Boot {@code server.max-http-response-header-size}：响应头总大小上限（字节，默认 8KB）。 实现见 {@code ResponseLimitConfig}
     * + {@code NettyServerHttpResponse}（P1-4.9）。
     */
    public static final String MAX_HTTP_RESPONSE_HEADER_SIZE = "server.max-http-response-header-size";
    public static final int MAX_HTTP_RESPONSE_HEADER_SIZE_DEFAULT = 8192;

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
     * Netty transport type: "auto" / "nio" / "epoll". auto（默认）：Linux 上 epoll 可用时自动使用 native epoll，否则回退
     * NIO（Windows/macOS）。 nio：强制 Java NIO。epoll：强制 native epoll，不可用时启动失败。
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
     * Access log format pattern. Supported tokens: %h (remote addr), %m (method), %U (URI), %T (elapsed ms), %s
     * (status), %u (user-agent), literal text otherwise. Default: "%h %m %U %Tms %s \"%u\""
     */
    public static final String ACCESSLOG_FORMAT = "server.accesslog.format";

    /** 访问日志落盘目录（对齐 server.tomcat.accesslog.directory）。未配置则不落盘，仅走日志框架。 */
    public static final String ACCESSLOG_DIRECTORY = "server.accesslog.directory";
    /** 访问日志文件名前缀（默认 access）。 */
    public static final String ACCESSLOG_PREFIX = "server.accesslog.prefix";
    public static final String ACCESSLOG_PREFIX_DEFAULT = "access";
    /** 访问日志文件名后缀（默认 .log）。 */
    public static final String ACCESSLOG_SUFFIX = "server.accesslog.suffix";
    public static final String ACCESSLOG_SUFFIX_DEFAULT = ".log";
    /** 是否按天轮转（默认 true）。 */
    public static final String ACCESSLOG_ROTATE = "server.accesslog.rotate";
    public static final boolean ACCESSLOG_ROTATE_DEFAULT = true;
    /** 日志保留天数（默认 7，小于等于 0 表示不限制）。 */
    public static final String ACCESSLOG_MAX_DAYS = "server.accesslog.max-days";
    public static final int ACCESSLOG_MAX_DAYS_DEFAULT = 7;

    // ========== 默认值查询 ==========

    private static final Map<String, Long> DEFAULTS = new HashMap<>();

    static {
        DEFAULTS.put(SERVER_PORT, (long) SERVER_PORT_DEFAULT);
        DEFAULTS.put(HTTP_MAX_CONTENT_LENGTH, (long) HTTP_MAX_CONTENT_LENGTH_DEFAULT);
        DEFAULTS.put(HTTP_TIMEOUT, HTTP_TIMEOUT_DEFAULT);
        DEFAULTS.put(HTTP_READ_TIMEOUT, HTTP_READ_TIMEOUT_DEFAULT);
        DEFAULTS.put(POOL_CORE_POOL_SIZE, (long) POOL_CORE_POOL_SIZE_DEFAULT);
        DEFAULTS.put(POOL_MAX_POOL_SIZE, (long) POOL_MAX_POOL_SIZE_DEFAULT);
        DEFAULTS.put(POOL_KEEP_ALIVE_TIME, (long) POOL_KEEP_ALIVE_TIME_DEFAULT);
        DEFAULTS.put(POOL_QUEUE_CAPACITY, (long) POOL_QUEUE_CAPACITY_DEFAULT);
        DEFAULTS.put(SERVER_NETTY_WORKERS, (long) SERVER_NETTY_WORKERS_DEFAULT);
        DEFAULTS.put(WRITE_BUFFER_LOW_WATERMARK, (long) WRITE_BUFFER_LOW_WATERMARK_DEFAULT);
        DEFAULTS.put(WRITE_BUFFER_HIGH_WATERMARK, (long) WRITE_BUFFER_HIGH_WATERMARK_DEFAULT);
        DEFAULTS.put(HTTP_MAX_INITIAL_LINE_LENGTH, (long) HTTP_MAX_INITIAL_LINE_LENGTH_DEFAULT);
        DEFAULTS.put(HTTP_MAX_CHUNK_SIZE, (long) HTTP_MAX_CHUNK_SIZE_DEFAULT);
        DEFAULTS.put(SERVER_NETTY_BOSS_THREADS, (long) SERVER_NETTY_BOSS_THREADS_DEFAULT);
        DEFAULTS.put(SERVER_NETTY_SO_BACKLOG, (long) SERVER_NETTY_SO_BACKLOG_DEFAULT);
        DEFAULTS.put(HTTP_MAX_IN_MEMORY_SIZE, (long) HTTP_MAX_IN_MEMORY_SIZE_DEFAULT);
        DEFAULTS.put(HTTP_MAX_REQUEST_HEADER_SIZE, (long) HTTP_MAX_REQUEST_HEADER_SIZE_DEFAULT);
        DEFAULTS.put(SERVER_SHUTDOWN_GRACE_PERIOD, SERVER_SHUTDOWN_GRACE_PERIOD_DEFAULT);
        DEFAULTS.put(ASYNC_REQUEST_TIMEOUT, ASYNC_REQUEST_TIMEOUT_DEFAULT);
        DEFAULTS.put(KEEP_ALIVE_TIMEOUT, KEEP_ALIVE_TIMEOUT_DEFAULT);
        DEFAULTS.put(MAX_KEEP_ALIVE_REQUESTS, (long) MAX_KEEP_ALIVE_REQUESTS_DEFAULT);
        DEFAULTS.put(MAX_SWALLOW_SIZE, MAX_SWALLOW_SIZE_DEFAULT);
        DEFAULTS.put(MAX_HTTP_RESPONSE_HEADER_SIZE, (long) MAX_HTTP_RESPONSE_HEADER_SIZE_DEFAULT);
        DEFAULTS.put(ACCESSLOG_MAX_DAYS, (long) ACCESSLOG_MAX_DAYS_DEFAULT);
        DEFAULTS.put(SERVER_MAX_CONNECTIONS, (long) SERVER_MAX_CONNECTIONS_DEFAULT);
        DEFAULTS.put(SERVER_MAX_PARAMETER_COUNT, (long) SERVER_MAX_PARAMETER_COUNT_DEFAULT);
        DEFAULTS.put(HTTP_MULTIPART_MAX_PART_COUNT, (long) HTTP_MULTIPART_MAX_PART_COUNT_DEFAULT);
        DEFAULTS.put(HTTP_MULTIPART_MAX_PART_HEADER_SIZE, (long) HTTP_MULTIPART_MAX_PART_HEADER_SIZE_DEFAULT);
    }

    /**
     * 根据 key 返回 long 默认值（int 属性也统一存为 long）。 仅在首次 cache miss 时调用一次，非热点路径。
     */
    public static long getDefault(String key) {
        return DEFAULTS.getOrDefault(key, 0L);
    }
}

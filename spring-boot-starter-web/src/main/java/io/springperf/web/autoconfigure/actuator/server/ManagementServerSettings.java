package io.springperf.web.autoconfigure.actuator.server;

import io.netty.handler.ssl.SslContext;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.server.CompressionConfig;
import io.springperf.web.server.KeepAliveConfig;
import io.springperf.web.server.ResponseLimitConfig;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 管理端口服务器的启动期不可变配置快照。
 *
 * <p>
 * <b>配置语义（对齐 Spring Boot 的「默认继承 + 窄覆盖」模型）</b>：管理端口默认与主服务器使用完全相同的
 * {@code server.*} 配置；仅当显式配置白名单内的 {@code management.server.*} 键时才覆盖该值。解析规则统一为
 * {@code management.server.X → server.X → 框架默认值}，由一个带覆盖属性源的 scoped {@link Environment} 实现，
 * 不为每个配置键单独写分支。
 * </p>
 *
 * <p>
 * <b>作用域分层</b>：
 * </p>
 * <ul>
 * <li>L1（公开可覆盖）：port / address / ssl.* / max-content-length / max-connections / shutdown.grace-period；</li>
 * <li>L2（允许但不宣传，协议栈参数）：http2.enabled / netty.transport / compression.* / keep-alive / 各类 HTTP 限制。
 * 同一开关的所有消费点（ALPN 与 HTTP/2 管线）必须读本快照的同一个字段；</li>
 * <li>L3（禁止覆盖）：最终落到共享 {@code WebContext} 单例上的键（如 {@code server.http.timeout}、
 * {@code server.max-parameter-count}、{@code server.servlet.*}、{@code pool.*}）。这类键配置了
 * {@code management.server.*} 镜像时，{@link #validate(Environment)} 在启动期直接抛出
 * {@link IllegalStateException}，避免「配置写了但静默不生效」。</li>
 * </ul>
 *
 * <p>
 * <b>SSL 开关同样遵循回退</b>：{@code management.server.ssl.enabled} 未配置时继承
 * {@code server.ssl.enabled}。因此主服务器显式关闭 SSL、但管理端口需要 HTTPS 时，必须显式配置
 * {@code management.server.ssl.enabled=true} 并给出管理侧证书；仅配置证书不足以覆盖继承来的关闭开关。
 * </p>
 *
 * <p>
 * <b>生命周期</b>：本快照在 bean 创建期一次性解析并固化，Spring Cloud 的
 * {@code EnvironmentChangeEvent} 只会清空共享 {@code WebContext} 的配置缓存，<b>不会</b>重建服务器级配置
 * （port / SSL / EventLoop 线程数 / 管线限制等），变更这些配置需要重启应用。
 * </p>
 */
public final class ManagementServerSettings {

    private static final String SERVER_PREFIX = "server.";
    private static final String MANAGEMENT_PREFIX = "management.server.";
    private static final String MANAGEMENT_PORT = MANAGEMENT_PREFIX + "port";
    /** 历史键：早期版本管理端口的 body 上限与 {@code server.http.max-content-length} 不同名，继续优先支持。 */
    private static final String LEGACY_MAX_CONTENT_LENGTH = MANAGEMENT_PREFIX + "max-content-length";
    private static final String SSL_PROPERTIES_PREFIX = "server.ssl.";

    /** L1 + L2 白名单：允许以 {@code management.server.<suffix>} 覆盖 {@code server.<suffix>} 的精确键。 */
    private static final Set<String> OVERRIDABLE_KEYS = Set.of("port", "address", "http2.enabled",
            "http.max-content-length", "max-content-length", "max-connections", "shutdown.grace-period",
            "netty.transport", "http.read-timeout", "http.max-initial-line-length", "http.max-chunk-size",
            "max-http-request-header-size", "http.multipart.max-part-count", "http.multipart.max-part-header-size",
            "keep-alive-timeout", "max-keep-alive-requests", "max-swallow-size", "max-http-response-header-size");

    /** 整组可覆盖的前缀（嵌套配置，如 {@code ssl.key-store} / {@code compression.mime-types}）。 */
    private static final List<String> OVERRIDABLE_PREFIXES = List.of("ssl.", "compression.");

    /** {@link PropertiesConstant} 中全部 {@code server.*} 键的后缀，用于识别 L3 镜像键。 */
    private static final Set<String> SERVER_KEYS = collectServerKeys();

    private final int port;
    private final int maxContentLength;
    private final SslContext sslContext;
    private final boolean http2Enabled;
    private final long shutdownGraceMillis;
    private final int maxConnections;
    private final String transport;
    private final String bindAddress;
    private final CompressionConfig compression;
    private final KeepAliveConfig keepAlive;
    private final ResponseLimitConfig responseLimit;
    private final long readTimeoutMillis;
    private final int maxInitialLineLength;
    private final int maxRequestHeaderSize;
    private final int maxChunkSize;
    private final int multipartMaxPartCount;
    private final int multipartMaxPartHeaderSize;

    private ManagementServerSettings(int port, int maxContentLength, SslContext sslContext, boolean http2Enabled,
            ApplicationProperties props) {
        this.port = port;
        this.maxContentLength = maxContentLength;
        this.sslContext = sslContext;
        this.http2Enabled = http2Enabled;
        this.shutdownGraceMillis = props.getDurationMillis(PropertiesConstant.SERVER_SHUTDOWN_GRACE_PERIOD,
                PropertiesConstant.SERVER_SHUTDOWN_GRACE_PERIOD_DEFAULT);
        this.maxConnections = props.getInt(PropertiesConstant.SERVER_MAX_CONNECTIONS);
        this.transport = props.get(PropertiesConstant.SERVER_NETTY_TRANSPORT,
                PropertiesConstant.SERVER_NETTY_TRANSPORT_DEFAULT);
        this.bindAddress = props.get(PropertiesConstant.SERVER_ADDRESS, null);
        this.compression = CompressionConfig.fromProperties(props);
        this.keepAlive = KeepAliveConfig.fromProperties(props);
        this.responseLimit = ResponseLimitConfig.fromProperties(props);
        this.readTimeoutMillis = props.getDurationMillis(PropertiesConstant.HTTP_READ_TIMEOUT,
                PropertiesConstant.HTTP_READ_TIMEOUT_DEFAULT);
        this.maxInitialLineLength = props.getInt(PropertiesConstant.HTTP_MAX_INITIAL_LINE_LENGTH);
        this.maxRequestHeaderSize = props.getInt(PropertiesConstant.HTTP_MAX_REQUEST_HEADER_SIZE);
        this.maxChunkSize = props.getInt(PropertiesConstant.HTTP_MAX_CHUNK_SIZE);
        this.multipartMaxPartCount = props.getInt(PropertiesConstant.HTTP_MULTIPART_MAX_PART_COUNT);
        this.multipartMaxPartHeaderSize = props.getInt(PropertiesConstant.HTTP_MULTIPART_MAX_PART_HEADER_SIZE);
    }

    /**
     * 从应用 {@link Environment} 解析管理端口配置：先校验 L3 覆盖，再构造 scoped Environment 完成统一回退。
     * <p>
     * SSL 与 HTTP/2 使用同一个 {@code http2Enabled} 值：ALPN 协商与 Netty 管线不会读到不同来源。
     * </p>
     */
    public static ManagementServerSettings resolve(Environment environment) {
        if (environment == null) {
            throw new IllegalArgumentException("environment must not be null");
        }
        validate(environment);
        Environment scoped = scopedEnvironment(environment);
        ApplicationProperties props = new ApplicationProperties(scoped);
        boolean http2Enabled = props.getBoolean(PropertiesConstant.HTTP2_ENABLED, false);
        SslContext sslContext = SslContextFactory.createServerSslContext(scoped, SSL_PROPERTIES_PREFIX, http2Enabled);
        Integer legacyMaxContentLength = environment.getProperty(LEGACY_MAX_CONTENT_LENGTH, Integer.class);
        int maxContentLength = legacyMaxContentLength != null ? legacyMaxContentLength
                : props.getInt(PropertiesConstant.HTTP_MAX_CONTENT_LENGTH);
        int port = environment.getProperty(MANAGEMENT_PORT, int.class, 0);
        return new ManagementServerSettings(port, maxContentLength, sslContext, http2Enabled, props);
    }

    /**
     * 兼容旧构造器 / 单元测试：直接从给定的 {@link ApplicationProperties} 解析，不做前缀回退。
     * <p>
     * {@code props} 为 null 时（纯构造测试，未 stub WebContext）使用空 Environment，所有值取框架默认值。
     * </p>
     */
    public static ManagementServerSettings fromApplicationProperties(ApplicationProperties props, int port,
            int maxContentLength, SslContext sslContext) {
        ApplicationProperties source = props != null ? props : new ApplicationProperties(new StandardEnvironment());
        boolean http2Enabled = source.getBoolean(PropertiesConstant.HTTP2_ENABLED, false);
        return new ManagementServerSettings(port, maxContentLength, sslContext, http2Enabled, source);
    }

    /**
     * L3 fail-fast：拒绝「镜像了 {@code server.*} 键、但管理服务器无法独立承载」的覆盖。
     * <p>
     * 只检查与 {@link PropertiesConstant} 中已知 {@code server.*} 键同名的后缀，Spring Boot 自有的
     * {@code management.server.*} 键（如 {@code add-application-context-header}）不在其中，不会被误伤。
     * </p>
     */
    static void validate(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return;
        }
        List<String> unsupported = new ArrayList<>();
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (!name.startsWith(MANAGEMENT_PREFIX)) {
                    continue;
                }
                String suffix = name.substring(MANAGEMENT_PREFIX.length());
                if (suffix.isEmpty() || isOverridable(suffix) || !SERVER_KEYS.contains(suffix)
                        || unsupported.contains(name)) {
                    continue;
                }
                unsupported.add(name);
            }
        }
        if (!unsupported.isEmpty()) {
            throw new IllegalStateException("Unsupported management.server.* override(s) " + unsupported
                    + ": these settings belong to the shared WebContext and cannot be overridden for the management "
                    + "port. Supported overrides: " + OVERRIDABLE_KEYS + " plus ssl.* / compression.*");
        }
    }

    private static boolean isOverridable(String suffix) {
        if (OVERRIDABLE_KEYS.contains(suffix)) {
            return true;
        }
        for (String prefix : OVERRIDABLE_PREFIXES) {
            if (suffix.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构造「管理覆盖优先、主服务器兜底」的 Environment：覆盖属性源在最前，随后是应用原始属性源。
     * 覆盖源未命中时返回 null，{@link org.springframework.core.env.PropertySourcesPropertyResolver} 会继续查找
     * 后面的 {@code server.X}，从而实现缺省继承。
     */
    private static Environment scopedEnvironment(Environment environment) {
        StandardEnvironment scoped = new StandardEnvironment();
        MutablePropertySources sources = scoped.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        if (environment instanceof ConfigurableEnvironment configurable) {
            for (PropertySource<?> source : configurable.getPropertySources()) {
                sources.addLast(source);
            }
        } else {
            sources.addLast(new PropertySource<Object>("delegateEnvironment") {
                @Override
                public Object getProperty(String name) {
                    return environment.getProperty(name);
                }
            });
        }
        sources.addFirst(new ManagementOverridePropertySource(environment));
        return scoped;
    }

    private static Set<String> collectServerKeys() {
        Set<String> keys = new HashSet<>();
        for (Field field : PropertiesConstant.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            try {
                Object value = field.get(null);
                if (value instanceof String key && key.startsWith(SERVER_PREFIX)) {
                    keys.add(key.substring(SERVER_PREFIX.length()));
                }
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException("Failed to inspect PropertiesConstant server keys", ex);
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    /** 把白名单内的 {@code server.X} 查询映射到 {@code management.server.X}；未配置返回 null 以继续回退。 */
    private static final class ManagementOverridePropertySource extends PropertySource<Object> {

        private final Environment environment;

        ManagementOverridePropertySource(Environment environment) {
            super("managementServerOverrides");
            this.environment = environment;
        }

        @Override
        public Object getProperty(String name) {
            if (name == null || !name.startsWith(SERVER_PREFIX)) {
                return null;
            }
            String suffix = name.substring(SERVER_PREFIX.length());
            return isOverridable(suffix) ? environment.getProperty(MANAGEMENT_PREFIX + suffix) : null;
        }
    }

    public int getPort() {
        return port;
    }

    public int getMaxContentLength() {
        return maxContentLength;
    }

    public SslContext getSslContext() {
        return sslContext;
    }

    /** ALPN 与 HTTP/2 管线共用的唯一开关。 */
    public boolean getHttp2Enabled() {
        return http2Enabled;
    }

    public long getShutdownGraceMillis() {
        return shutdownGraceMillis;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public String getTransport() {
        return transport;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public CompressionConfig getCompression() {
        return compression;
    }

    public KeepAliveConfig getKeepAlive() {
        return keepAlive;
    }

    public ResponseLimitConfig getResponseLimit() {
        return responseLimit;
    }

    public long getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public int getMaxInitialLineLength() {
        return maxInitialLineLength;
    }

    public int getMaxRequestHeaderSize() {
        return maxRequestHeaderSize;
    }

    public int getMaxChunkSize() {
        return maxChunkSize;
    }

    public int getMultipartMaxPartCount() {
        return multipartMaxPartCount;
    }

    public int getMultipartMaxPartHeaderSize() {
        return multipartMaxPartHeaderSize;
    }
}

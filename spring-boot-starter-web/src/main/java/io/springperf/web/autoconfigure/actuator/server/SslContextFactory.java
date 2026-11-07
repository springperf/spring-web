package io.springperf.web.autoconfigure.actuator.server;

import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.server.Ssl;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.Arrays;

/**
 * Netty {@link SslContext} 工厂工具类。
 * <p>
 * 配置来源统一为 Spring Boot 的强类型 {@link Ssl} 模型：通过 {@link Binder} 把
 * {@code server.ssl.*}（主服务器）或 scoped Environment 下的 {@code server.ssl.*}
 * （管理服务器，由 {@code management.server.ssl.*} 覆盖后回退）绑定成同一个 {@link Ssl} 对象。
 * 主/管理两侧因此共享同一套属性命名、枚举校验、{@code enabled} 语义与 relaxed binding，
 * 不再各写一套字符串前缀解析。
 * </p>
 * <p>
 * <b>支持的 {@link Ssl} 字段</b>：{@code enabled}、{@code key-store}、
 * {@code key-store-password}、{@code key-store-type}、{@code key-password}、
 * {@code certificate}、{@code certificate-private-key}、{@code client-auth}、
 * {@code enabled-protocols}、{@code ciphers}、{@code trust-store}、
 * {@code trust-store-password}、{@code trust-store-type}。
 * 支持 PKCS12/JKS 密钥库与 PEM 证书两种格式；{@code classpath:} 资源以 stream 方式加载。
 * </p>
 * <p>
 * <b>启停语义（对齐 {@link Ssl#isEnabled(Ssl)}）</b>：未绑定任何 SSL 属性时视为未启用；
 * {@code enabled} 默认 {@code true}，配置 {@code key-store} 或 {@code certificate} 即隐式启用；
 * 显式 {@code enabled=false} 一律关闭，即使残留证书配置。
 * 非法 {@code client-auth} 值由 Binder 在启动期抛错，不再静默降级为 NONE。
 * </p>
 * <p>
 * <b>尚未支持</b>：{@code spring.ssl.bundle.*} / {@code server.ssl.bundle}、SNI、证书热重载；
 * 这类属性绑定后不参与构建，变更服务器级 SSL 配置需要重启应用。
 * </p>
 */
public final class SslContextFactory {

    private static final String DEFAULT_STORE_TYPE = "PKCS12";
    private static final DefaultResourceLoader RESOURCE_LOADER = new DefaultResourceLoader();

    private SslContextFactory() {
    }

    /**
     * 从 Environment 绑定 SSL 配置并创建 {@link SslContext}。
     *
     * @param env
     *            Spring Environment；主服务器传原始 Environment，管理服务器传带覆盖属性源的 scoped Environment
     * @param prefix
     *            属性前缀，如 {@code "server.ssl."}；仅用于定位绑定根名称，不再逐键拼接读取
     * @return SslContext，未配置 SSL 或显式关闭时返回 null
     */
    public static SslContext createServerSslContext(Environment env, String prefix) {
        return createServerSslContext(env, prefix, false);
    }

    /**
     * 创建 SslContext，可选配置 ALPN 用于 HTTP/2 协议协商。
     *
     * @param env
     *            Spring Environment
     * @param prefix
     *            SSL 属性前缀
     * @param http2Enabled
     *            是否启用 HTTP/2（配置 ALPN）
     * @return SslContext，未配置 SSL 或显式关闭时返回 null
     */
    public static SslContext createServerSslContext(Environment env, String prefix, boolean http2Enabled) {
        if (env == null) {
            return null;
        }
        return createServerSslContext(bind(env, prefix), http2Enabled);
    }

    /**
     * 强类型入口：从已经绑定好的 Boot {@link Ssl} 对象构建 {@link SslContext}。
     * 主服务器与管理服务器最终都汇聚到这里，便于未来接入 {@code SslBundle} / 证书热重载。
     */
    public static SslContext createServerSslContext(Ssl ssl, boolean http2Enabled) {
        if (!Ssl.isEnabled(ssl)) {
            return null;
        }
        try {
            SslContextBuilder builder = createBuilder(ssl);

            if (http2Enabled) {
                builder.applicationProtocolConfig(new ApplicationProtocolConfig(ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1));
            }

            String[] enabledProtocols = ssl.getEnabledProtocols();
            if (enabledProtocols != null && enabledProtocols.length > 0) {
                builder.protocols(enabledProtocols);
            }
            String[] ciphers = ssl.getCiphers();
            if (ciphers != null && ciphers.length > 0) {
                builder.ciphers(Arrays.asList(ciphers));
            }
            return builder.build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create SslContext from SSL configuration", e);
        }
    }

    /** 使用 Binder 绑定 Boot Ssl；非法值（如 client-auth=bogus）在此 fail-fast 并带上前缀上下文。 */
    private static Ssl bind(Environment env, String prefix) {
        String name = prefix.endsWith(".") ? prefix.substring(0, prefix.length() - 1) : prefix;
        try {
            return binderFor(env).bind(name, Bindable.of(Ssl.class)).orElse(null);
        } catch (BindException ex) {
            // 只包装绑定错误；NPE 等编程错误必须原样抛出，避免伪装成「配置无效」
            throw new IllegalStateException("Invalid SSL configuration at [" + name + "]: " + ex.getMessage(), ex);
        }
    }

    /**
     * Binder 的 {@code ConfigurationPropertySources#get} 要求 ConfigurableEnvironment。
     * 生产环境必然满足；对仍传入普通 Environment 的既有调用方，用委托属性源适配，保持 public API 兼容。
     */
    private static Binder binderFor(Environment env) {
        if (env instanceof ConfigurableEnvironment) {
            return Binder.get(env);
        }
        StandardEnvironment adapted = new StandardEnvironment();
        adapted.getPropertySources().addFirst(new PropertySource<Object>("sslEnvironmentDelegate") {
            @Override
            public Object getProperty(String name) {
                return env.getProperty(name);
            }
        });
        return Binder.get(adapted);
    }

    private static SslContextBuilder createBuilder(Ssl ssl) throws Exception {
        String certificate = ssl.getCertificate();
        String privateKey = ssl.getCertificatePrivateKey();
        if (hasText(certificate) && hasText(privateKey)) {
            // C7：stream-based 加载，支持 classpath: 前缀（JAR 内资源 getFile() 不可用）。
            try (InputStream certIn = openInputStream(certificate);
                    InputStream keyIn = openInputStream(privateKey)) {
                return SslContextBuilder.forServer(certIn, keyIn, ssl.getKeyPassword());
            }
        }

        String keyStorePath = ssl.getKeyStore();
        if (!hasText(keyStorePath)) {
            throw new IllegalStateException("SSL is enabled but neither certificate/certificate-private-key "
                    + "nor key-store is configured");
        }
        String keyStorePassword = ssl.getKeyStorePassword();
        String keyStoreType = hasText(ssl.getKeyStoreType()) ? ssl.getKeyStoreType() : DEFAULT_STORE_TYPE;
        String keyPassword = ssl.getKeyPassword() != null ? ssl.getKeyPassword() : keyStorePassword;

        KeyStore keyStore = KeyStore.getInstance(keyStoreType);
        try (InputStream in = openInputStream(keyStorePath)) {
            keyStore.load(in, keyStorePassword != null ? keyStorePassword.toCharArray() : null);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keyPassword != null ? keyPassword.toCharArray() : null);

        SslContextBuilder builder = SslContextBuilder.forServer(kmf);
        configureClientAuth(builder, ssl);
        return builder;
    }

    /** 客户端认证（mTLS）；client-auth 为 null 时不做任何设置，保持 Netty 默认。 */
    private static void configureClientAuth(SslContextBuilder builder, Ssl ssl) throws Exception {
        Ssl.ClientAuth clientAuth = ssl.getClientAuth();
        if (clientAuth == null) {
            return;
        }
        builder.clientAuth(clientAuth == Ssl.ClientAuth.NEED ? ClientAuth.REQUIRE
                : clientAuth == Ssl.ClientAuth.WANT ? ClientAuth.OPTIONAL : ClientAuth.NONE);

        String trustStorePath = ssl.getTrustStore();
        if (!hasText(trustStorePath)) {
            return;
        }
        String trustStoreType = hasText(ssl.getTrustStoreType()) ? ssl.getTrustStoreType() : DEFAULT_STORE_TYPE;
        String trustStorePassword = ssl.getTrustStorePassword();
        KeyStore trustStore = KeyStore.getInstance(trustStoreType);
        try (InputStream in = openInputStream(trustStorePath)) {
            trustStore.load(in, trustStorePassword != null ? trustStorePassword.toCharArray() : null);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        builder.trustManager(tmf);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static InputStream openInputStream(String path) throws Exception {
        if (path == null) {
            throw new IllegalArgumentException("resource path must not be null");
        }
        Resource resource = RESOURCE_LOADER.getResource(path);
        if (resource.exists()) {
            return resource.getInputStream();
        }
        return new FileInputStream(path);
    }
}

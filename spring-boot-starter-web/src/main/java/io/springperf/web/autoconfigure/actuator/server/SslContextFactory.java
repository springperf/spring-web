package io.springperf.web.autoconfigure.actuator.server;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import javax.net.ssl.KeyManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Netty {@link SslContext} 工厂工具类。
 * <p>从 Spring {@link Environment} 中读取 {@code server.ssl.*} 或 {@code management.server.ssl.*}
 * 配置属性，构建 Netty SslContext。支持 PKCS12/JKS 密钥库和 PEM 证书两种格式。</p>
 *
 * <h3>支持的属性</h3>
 * <ul>
 *   <li>{@code xxx.enabled} — 是否启用 SSL（配置 key-store 或 certificate 时隐式启用）</li>
 *   <li>{@code xxx.key-store} — 密钥库路径（支持 classpath: 前缀）</li>
 *   <li>{@code xxx.key-store-password} — 密钥库密码</li>
 *   <li>{@code xxx.key-store-type} — 密钥库类型（默认 PKCS12）</li>
 *   <li>{@code xxx.key-password} — 密钥密码（默认与 key-store-password 相同）</li>
 *   <li>{@code xxx.certificate} — PEM 证书文件路径</li>
 *   <li>{@code xxx.certificate-private-key} — PEM 私钥文件路径</li>
 *   <li>{@code xxx.client-auth} — 客户端认证模式：need / want / none</li>
 *   <li>{@code xxx.protocol} — SSL 协议（默认 TLS）</li>
 *   <li>{@code xxx.enabled-protocols} — 启用的协议列表（逗号分隔）</li>
 *   <li>{@code xxx.ciphers} — 启用的加密套件列表（逗号分隔）</li>
 * </ul>
 */
public final class SslContextFactory {

    private static final DefaultResourceLoader RESOURCE_LOADER = new DefaultResourceLoader();

    private SslContextFactory() {
    }

    /**
     * 从 Environment 读取指定前缀的 SSL 配置并创建 {@link SslContext}。
     *
     * @param env    Spring Environment
     * @param prefix 属性前缀，如 {@code "server.ssl."} 或 {@code "management.server.ssl."}
     * @return SslContext，未配置 SSL 时返回 null
     */
    public static SslContext createServerSslContext(Environment env, String prefix) {
        if (env == null || !isSslEnabled(env, prefix)) {
            return null;
        }

        try {
            SslContextBuilder builder;

            // PEM 优先（certificate + certificate-private-key）
            String certificate = env.getProperty(prefix + "certificate");
            String privateKey = env.getProperty(prefix + "certificate-private-key");
            if (certificate != null && privateKey != null) {
                File certFile = resolveFile(certificate);
                File keyFile = resolveFile(privateKey);
                String keyPassword = env.getProperty(prefix + "key-password");
                builder = SslContextBuilder.forServer(certFile, keyFile, keyPassword);
            } else {
                // JKS / PKCS12 密钥库
                String keyStorePath = env.getProperty(prefix + "key-store");
                String keyStorePassword = env.getProperty(prefix + "key-store-password");
                String keyStoreType = env.getProperty(prefix + "key-store-type", "PKCS12");
                String keyPassword = env.getProperty(prefix + "key-password", keyStorePassword);

                KeyStore keyStore = KeyStore.getInstance(keyStoreType);
                try (InputStream in = openInputStream(keyStorePath)) {
                    keyStore.load(in, keyStorePassword != null ? keyStorePassword.toCharArray() : null);
                }

                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(keyStore, keyPassword != null ? keyPassword.toCharArray() : null);
                builder = SslContextBuilder.forServer(kmf);
            }

            // 可选：启用的协议列表（如 "TLSv1.2,TLSv1.3"），不设置则使用 JDK 默认
            String enabledProtocols = env.getProperty(prefix + "enabled-protocols");
            if (enabledProtocols != null && !enabledProtocols.isEmpty()) {
                builder.protocols(splitByComma(enabledProtocols));
            }

            // 可选：加密套件
            String ciphers = env.getProperty(prefix + "ciphers");
            if (ciphers != null && !ciphers.isEmpty()) {
                builder.ciphers(Arrays.asList(splitByComma(ciphers)));
            }

            // 可选：客户端认证
            String clientAuth = env.getProperty(prefix + "client-auth");
            if (clientAuth != null) {
                switch (clientAuth.toLowerCase()) {
                    case "need":
                        builder.clientAuth(ClientAuth.REQUIRE);
                        break;
                    case "want":
                        builder.clientAuth(ClientAuth.OPTIONAL);
                        break;
                    default:
                        builder.clientAuth(ClientAuth.NONE);
                        break;
                }
            }

            return builder.build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create SslContext from properties [" + prefix + "]", e);
        }
    }

    /**
     * 判断 SSL 是否已配置启用。
     * 满足以下任一条件即视为启用：
     * <ol>
     *   <li>{@code xxx.enabled=true} 显式配置</li>
     *   <li>{@code xxx.key-store} 已配置</li>
     *   <li>{@code xxx.certificate} 已配置</li>
     * </ol>
     */
    private static boolean isSslEnabled(Environment env, String prefix) {
        Boolean enabled = env.getProperty(prefix + "enabled", Boolean.class);
        if (Boolean.TRUE.equals(enabled)) {
            return true;
        }
        if (env.containsProperty(prefix + "key-store")) {
            return true;
        }
        if (env.containsProperty(prefix + "certificate")) {
            return true;
        }
        return false;
    }

    private static File resolveFile(String path) {
        try {
            Resource resource = RESOURCE_LOADER.getResource(path);
            if (resource.exists()) {
                return resource.getFile();
            }
            // 兜底：直接作为文件路径
            return new File(path);
        } catch (Exception e) {
            return new File(path);
        }
    }

    private static InputStream openInputStream(String path) throws Exception {
        if (path == null) {
            throw new IllegalArgumentException("key-store path must not be null");
        }
        Resource resource = RESOURCE_LOADER.getResource(path);
        if (resource.exists()) {
            return resource.getInputStream();
        }
        return new FileInputStream(path);
    }

    private static String[] splitByComma(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList())
                .toArray(new String[0]);
    }
}

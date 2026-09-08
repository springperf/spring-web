package io.springperf.web.autoconfigure.actuator.server;

import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;

import java.io.InputStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SslContextFactoryTest {

    @Mock Environment env;

    @Test
    void createServerSslContext_nullEnv_returnsNull() {
        assertNull(SslContextFactory.createServerSslContext(null, "server.ssl."));
    }

    @Test
    void createServerSslContext_notEnabled_returnsNull() {
        when(env.getProperty(eq("server.ssl.enabled"), eq(Boolean.class))).thenReturn(null);
        when(env.containsProperty("server.ssl.key-store")).thenReturn(false);
        when(env.containsProperty("server.ssl.certificate")).thenReturn(false);

        assertNull(SslContextFactory.createServerSslContext(env, "server.ssl."));
    }

    @Test
    void createServerSslContext_enabledExplicitly_throwsNoKeyStore() {
        when(env.getProperty(eq("server.ssl.enabled"), eq(Boolean.class))).thenReturn(true);

        assertThrows(IllegalStateException.class,
                () -> SslContextFactory.createServerSslContext(env, "server.ssl."));
    }

    @Test
    void isSslEnabled_enabledTrue() throws Exception {
        when(env.getProperty(eq("test.ssl.enabled"), eq(Boolean.class))).thenReturn(true);

        boolean result = invokeIsSslEnabled(env, "test.ssl.");
        assertTrue(result);
    }

    @Test
    void isSslEnabled_enabledFalse_noKeyStore_noCertificate() throws Exception {
        when(env.getProperty(eq("test.ssl.enabled"), eq(Boolean.class))).thenReturn(false);
        when(env.containsProperty("test.ssl.key-store")).thenReturn(false);
        when(env.containsProperty("test.ssl.certificate")).thenReturn(false);

        boolean result = invokeIsSslEnabled(env, "test.ssl.");
        assertFalse(result);
    }

    @Test
    void isSslEnabled_enabledNull_hasKeyStore() throws Exception {
        when(env.getProperty(eq("test.ssl.enabled"), eq(Boolean.class))).thenReturn(null);
        when(env.containsProperty("test.ssl.key-store")).thenReturn(true);

        boolean result = invokeIsSslEnabled(env, "test.ssl.");
        assertTrue(result);
    }

    @Test
    void isSslEnabled_enabledNull_hasCertificate() throws Exception {
        when(env.getProperty(eq("test.ssl.enabled"), eq(Boolean.class))).thenReturn(null);
        when(env.containsProperty("test.ssl.key-store")).thenReturn(false);
        when(env.containsProperty("test.ssl.certificate")).thenReturn(true);

        boolean result = invokeIsSslEnabled(env, "test.ssl.");
        assertTrue(result);
    }

    @Test
    void splitByComma_singleValue() throws Exception {
        String[] result = invokeSplitByComma("TLSv1.2");
        assertArrayEquals(new String[]{"TLSv1.2"}, result);
    }

    @Test
    void splitByComma_multipleValues() throws Exception {
        String[] result = invokeSplitByComma("TLSv1.2,TLSv1.3");
        assertArrayEquals(new String[]{"TLSv1.2", "TLSv1.3"}, result);
    }

    @Test
    void splitByComma_withSpaces() throws Exception {
        String[] result = invokeSplitByComma(" TLSv1.2 , TLSv1.3 ");
        assertArrayEquals(new String[]{"TLSv1.2", "TLSv1.3"}, result);
    }

    @Test
    void splitByComma_emptyEntriesFiltered() throws Exception {
        String[] result = invokeSplitByComma("TLSv1.2,,TLSv1.3");
        assertArrayEquals(new String[]{"TLSv1.2", "TLSv1.3"}, result);
    }

    @Test
    void splitByComma_emptyString() throws Exception {
        String[] result = invokeSplitByComma("");
        assertArrayEquals(new String[0], result);
    }

    /* ==================== C7: PEM stream-based 加载（JAR 内资源 getFile() 不可用） ==================== */

    @Test
    void openInputStream_classpathResource_returnsReadableStream() throws Exception {
        // 回归 C7：classpath: 前缀必须走 Resource.getInputStream()（stream-based），
        // 而非 resource.getFile()——后者在 JAR 内抛 FileNotFoundException。
        Method m = SslContextFactory.class.getDeclaredMethod("openInputStream", String.class);
        m.setAccessible(true);

        try (InputStream in = (InputStream) m.invoke(null, "classpath:ssl/cert.pem")) {
            assertNotNull(in);
            assertTrue(in.available() > 0, "classpath 证书资源必须可读");
        }
    }

    @Test
    void createServerSslContext_pemFromClasspath_streamBased() {
        when(env.getProperty(eq("server.ssl.enabled"), eq(Boolean.class))).thenReturn(null);
        when(env.containsProperty("server.ssl.key-store")).thenReturn(false);
        when(env.containsProperty("server.ssl.certificate")).thenReturn(true);
        when(env.getProperty(eq("server.ssl.certificate"))).thenReturn("classpath:ssl/cert.pem");
        when(env.getProperty(eq("server.ssl.certificate-private-key"))).thenReturn("classpath:ssl/key.pem");
        // key-password / enabled-protocols / ciphers / client-auth 均未配置（mock 默认 null）

        SslContext ctx = SslContextFactory.createServerSslContext(env, "server.ssl.");

        assertNotNull(ctx, "classpath PEM 配置应能成功构建 SslContext");
    }

    // ==================== TLS 深度：client-auth / protocols / ciphers 分支 ====================

    private void stubPemServer() {
        org.mockito.Mockito.lenient().when(env.getProperty(eq("server.ssl.enabled"), eq(Boolean.class))).thenReturn(null);
        org.mockito.Mockito.lenient().when(env.containsProperty("server.ssl.key-store")).thenReturn(false);
        org.mockito.Mockito.lenient().when(env.containsProperty("server.ssl.certificate")).thenReturn(true);
        org.mockito.Mockito.lenient().when(env.getProperty(eq("server.ssl.certificate"))).thenReturn("classpath:ssl/cert.pem");
        org.mockito.Mockito.lenient().when(env.getProperty(eq("server.ssl.certificate-private-key"))).thenReturn("classpath:ssl/key.pem");
    }

    @Test
    void createServerSslContext_clientAuthNeed_builds() {
        stubPemServer();
        when(env.getProperty(eq("server.ssl.client-auth"))).thenReturn("need");

        SslContext ctx = SslContextFactory.createServerSslContext(env, "server.ssl.");
        assertNotNull(ctx, "client-auth=need 应构建 SslContext（mTLS REQUIRE）");
    }

    @Test
    void createServerSslContext_clientAuthWant_builds() {
        stubPemServer();
        when(env.getProperty(eq("server.ssl.client-auth"))).thenReturn("want");

        SslContext ctx = SslContextFactory.createServerSslContext(env, "server.ssl.");
        assertNotNull(ctx, "client-auth=want 应构建 SslContext（mTLS OPTIONAL）");
    }

    @Test
    void createServerSslContext_clientAuthNone_builds() {
        stubPemServer();
        when(env.getProperty(eq("server.ssl.client-auth"))).thenReturn("none");

        SslContext ctx = SslContextFactory.createServerSslContext(env, "server.ssl.");
        assertNotNull(ctx, "client-auth=none 应构建 SslContext（无客户端认证）");
    }

    @Test
    void createServerSslContext_clientAuthUnknown_buildsAsNone() {
        stubPemServer();
        when(env.getProperty(eq("server.ssl.client-auth"))).thenReturn("bogus");

        assertDoesNotThrow(() -> SslContextFactory.createServerSslContext(env, "server.ssl."),
                "未知 client-auth 值应回退为 NONE 而非抛异常");
    }

    @Test
    void createServerSslContext_clientAuthNeed_withTrustStore_builds() throws Exception {
        stubPemServer();
        when(env.getProperty(eq("server.ssl.client-auth"))).thenReturn("need");
        // 用 cert.pem 生成临时 PKCS12 trust-store（mTLS 验证客户端证书链用）
        java.io.File trustStore = createPkcs12TrustStoreFromCert();
        when(env.getProperty(eq("server.ssl.trust-store"))).thenReturn(trustStore.getAbsolutePath());
        when(env.getProperty(eq("server.ssl.trust-store-password"))).thenReturn("changeit");
        when(env.getProperty(eq("server.ssl.trust-store-type"), eq("PKCS12"))).thenReturn("PKCS12");

        SslContext ctx = SslContextFactory.createServerSslContext(env, "server.ssl.");

        assertNotNull(ctx, "client-auth=need + trust-store 应构建支持 mTLS 的 SslContext");
        trustStore.delete();
    }

    /** 从 classpath ssl/cert.pem 读取证书并写入临时 PKCS12 信任库，供 mTLS trust-store 配置测试使用 */
    private java.io.File createPkcs12TrustStoreFromCert() throws Exception {
        java.security.cert.CertificateFactory cf =
                java.security.cert.CertificateFactory.getInstance("X.509");
        try (InputStream in = SslContextFactoryTest.class.getResourceAsStream("/ssl/cert.pem")) {
            java.security.cert.Certificate cert = cf.generateCertificate(in);
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setCertificateEntry("test-ca", cert);
            java.io.File f = java.io.File.createTempFile("truststore", ".p12");
            f.deleteOnExit();
            try (java.io.OutputStream os = new java.io.FileOutputStream(f)) {
                ks.store(os, "changeit".toCharArray());
            }
            return f;
        }
    }

    @Test
    void createServerSslContext_enabledProtocols_builds() {
        stubPemServer();
        when(env.getProperty(eq("server.ssl.enabled-protocols"))).thenReturn("TLSv1.2,TLSv1.3");

        assertDoesNotThrow(() -> SslContextFactory.createServerSslContext(env, "server.ssl."),
                "enabled-protocols 应成功构建（版本协商配置）");
    }

    @Test
    void createServerSslContext_ciphers_builds() {
        stubPemServer();
        when(env.getProperty(eq("server.ssl.ciphers"))).thenReturn("TLS_AES_128_GCM_SHA256");

        assertDoesNotThrow(() -> SslContextFactory.createServerSslContext(env, "server.ssl."),
                "ciphers 应成功构建（加密套件配置）");
    }

    /* ==================== 补充：http2 ALPN / key-store 分支 / openInputStream 回退 ==================== */

    @Test
    void createServerSslContext_http2Enabled_addsAlpn() {
        stubPemServer();

        SslContext ctx = SslContextFactory.createServerSslContext(env, "server.ssl.", true);

        assertNotNull(ctx, "http2Enabled=true 应配置 ALPN 后构建 SslContext");
    }

    @Test
    void createServerSslContext_pkcs12KeyStore_builds() throws Exception {
        java.io.File keyStore = generatePkcs12KeyStore();
        try {
            when(env.getProperty(eq("server.ssl.enabled"), eq(Boolean.class))).thenReturn(null);
            when(env.containsProperty("server.ssl.key-store")).thenReturn(true);
            when(env.containsProperty("server.ssl.certificate")).thenReturn(false);
            when(env.getProperty(eq("server.ssl.key-store"))).thenReturn(keyStore.getAbsolutePath());
            when(env.getProperty(eq("server.ssl.key-store-password"))).thenReturn("changeit");
            when(env.getProperty(eq("server.ssl.key-store-type"), eq("PKCS12"))).thenReturn("PKCS12");
            when(env.getProperty(eq("server.ssl.key-password"), eq("changeit"))).thenReturn("changeit");

            SslContext ctx = SslContextFactory.createServerSslContext(env, "server.ssl.");

            assertNotNull(ctx, "PKCS12 密钥库配置应能构建 SslContext");
        } finally {
            keyStore.delete();
        }
    }

    @Test
    void openInputStream_missingResource_throwsIllegalArgument() throws Exception {
        Method m = SslContextFactory.class.getDeclaredMethod("openInputStream", String.class);
        m.setAccessible(true);
        java.lang.reflect.InvocationTargetException ex = assertThrows(
                java.lang.reflect.InvocationTargetException.class, () -> m.invoke(null, (Object) null));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause(),
                "openInputStream(null) 应抛 IllegalArgumentException");
    }

    @Test
    void openInputStream_filePath_fallsBackToFileInput() throws Exception {
        java.io.File f = java.io.File.createTempFile("cert", ".pem");
        try (java.io.InputStream src = SslContextFactoryTest.class.getResourceAsStream("/ssl/cert.pem");
             java.io.OutputStream os = new java.io.FileOutputStream(f)) {
            src.transferTo(os);
        }
        Method m = SslContextFactory.class.getDeclaredMethod("openInputStream", String.class);
        m.setAccessible(true);
        try (InputStream in = (InputStream) m.invoke(null, f.getAbsolutePath())) {
            assertNotNull(in);
            assertTrue(in.available() > 0);
        } finally {
            f.delete();
        }
    }

    /** 生成含自签密钥对的 PKCS12 密钥库（仅 JDK + keytool，不引入第三方库）。 */
    private java.io.File generatePkcs12KeyStore() throws Exception {
        String keytoolPath = System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator
                + (System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool");
        java.io.File ksFile = java.io.File.createTempFile("keystore", ".p12");
        ksFile.delete(); // keytool 拒绝覆盖已存在文件，先删除占位
        ksFile.deleteOnExit();
        Process p = new ProcessBuilder(keytoolPath,
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", ksFile.getAbsolutePath(),
                "-storepass", "changeit", "-keypass", "changeit",
                "-dname", "CN=localhost").redirectErrorStream(true).start();
        String output = readAllOutput(p.getInputStream());
        p.waitFor();
        if (p.exitValue() != 0) {
            throw new IllegalStateException("keytool 生成密钥库失败: " + output);
        }
        return ksFile;
    }

    private static String readAllOutput(java.io.InputStream in) throws Exception {
        try (in; java.util.Scanner sc = new java.util.Scanner(in, "UTF-8")) {
            sc.useDelimiter("\\A");
            return sc.hasNext() ? sc.next() : "";
        }
    }

    private static boolean invokeIsSslEnabled(Environment env, String prefix) throws Exception {
        Method method = SslContextFactory.class.getDeclaredMethod("isSslEnabled", Environment.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, env, prefix);
    }

    private static String[] invokeSplitByComma(String value) throws Exception {
        Method method = SslContextFactory.class.getDeclaredMethod("splitByComma", String.class);
        method.setAccessible(true);
        return (String[]) method.invoke(null, value);
    }
}

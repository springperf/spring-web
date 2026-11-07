package io.springperf.web.autoconfigure.actuator.server;

import static org.junit.jupiter.api.Assertions.*;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.server.Ssl;

/** SSL 配置的单侧证书、客户端认证、空数组回退与资源加载三态。 */
class SslContextFactoryDefaultsTest {

    private static final String CERT = "classpath:ssl/cert.pem";
    private static final String KEY = "classpath:ssl/key.pem";

    private static Ssl pemSsl() {
        Ssl ssl = new Ssl();
        ssl.setEnabled(true);
        ssl.setCertificate(CERT);
        ssl.setCertificatePrivateKey(KEY);
        return ssl;
    }

    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = SslContextFactory.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void certificateWithoutPrivateKeyFallsThroughToMissingKeyStoreFailure() {
        Ssl ssl = new Ssl();
        ssl.setEnabled(true);
        ssl.setCertificate(CERT);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SslContextFactory.createServerSslContext(ssl, false));
        assertNotNull(ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("neither certificate/certificate-private-key"));
    }

    @Test
    void privateKeyWithoutCertificateFallsThroughToMissingKeyStoreFailure() {
        Ssl ssl = new Ssl();
        ssl.setEnabled(true);
        ssl.setCertificatePrivateKey(KEY);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SslContextFactory.createServerSslContext(ssl, false));
        assertNotNull(ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("nor key-store"));
    }

    @Test
    void clientAuthNullSkipsTrustStoreLoading() {
        Ssl ssl = pemSsl();
        ssl.setTrustStore("classpath:no-such-truststore.p12");

        assertNotNull(SslContextFactory.createServerSslContext(ssl, false),
                "未配置 client-auth 时不应加载 trust-store");
    }

    @Test
    void clientAuthVariantsWithoutTrustStoreBuild() {
        for (Ssl.ClientAuth auth : new Ssl.ClientAuth[] { Ssl.ClientAuth.NEED, Ssl.ClientAuth.WANT,
                Ssl.ClientAuth.NONE }) {
            Ssl ssl = pemSsl();
            ssl.setClientAuth(auth);
            assertNotNull(SslContextFactory.createServerSslContext(ssl, false), "client-auth=" + auth);
        }
    }

    @Test
    void emptyProtocolsAndCiphersAreIgnored() {
        Ssl ssl = pemSsl();
        ssl.setEnabledProtocols(new String[0]);
        ssl.setCiphers(new String[0]);

        assertNotNull(SslContextFactory.createServerSslContext(ssl, false));
    }

    @Test
    void http2EnabledWithPemAddsAlpn() {
        assertNotNull(SslContextFactory.createServerSslContext(pemSsl(), true));
    }

    @Test
    void openInputStreamRejectsNullPath() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> invoke("openInputStream", new Class<?>[] { String.class }, (Object) null));
        assertTrue(ex.getMessage().contains("must not be null"));
    }

    @Test
    void openInputStreamReadsClasspathResource() throws Exception {
        try (InputStream in = (InputStream) invoke("openInputStream", new Class<?>[] { String.class }, CERT)) {
            assertTrue(in.read() > 0, "classpath 证书应可读");
        }
    }

    @Test
    void openInputStreamFallsBackToFilesystem(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("cert.pem");
        Files.write(file, "filesystem".getBytes(StandardCharsets.UTF_8));

        try (InputStream in = (InputStream) invoke("openInputStream", new Class<?>[] { String.class },
                file.toString())) {
            assertEquals("filesystem", new String(readAll(in), StandardCharsets.UTF_8));
        }
    }

    @Test
    void openInputStreamMissingPathFailsFast(@TempDir Path dir) {
        String missing = dir.resolve("missing.pem").toString();
        assertThrows(FileNotFoundException.class,
                () -> invoke("openInputStream", new Class<?>[] { String.class }, missing));
    }

    @Test
    void hasTextCoversNullBlankAndText() throws Exception {
        assertFalse((Boolean) invoke("hasText", new Class<?>[] { String.class }, (Object) null));
        assertFalse((Boolean) invoke("hasText", new Class<?>[] { String.class }, ""));
        assertFalse((Boolean) invoke("hasText", new Class<?>[] { String.class }, "   "));
        assertTrue((Boolean) invoke("hasText", new Class<?>[] { String.class }, " value "));
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}

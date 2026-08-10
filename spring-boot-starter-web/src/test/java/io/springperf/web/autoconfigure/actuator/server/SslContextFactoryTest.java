package io.springperf.web.autoconfigure.actuator.server;

import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

import java.io.InputStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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
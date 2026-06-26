package io.springperf.web.autoconfigure.actuator.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

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
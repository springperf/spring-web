package io.springperf.web.core.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.arg.resolver.HttpEntityArgResolver;
import io.springperf.web.core.codec.HttpBodyCodecRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpEntity;
import org.springframework.http.RequestEntity;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;

@ExtendWith(MockitoExtension.class)
class HttpEntityResolverProviderTest {

    HttpEntityResolverProvider provider = new HttpEntityResolverProvider();

    @Mock
    WebContext webContext;

    @Test
    void supports_httpEntityType_returnsTrue() throws Exception {
        Method method = getClass().getMethod("entityParam", HttpEntity.class);
        MethodParameter mp = new MethodParameter(method, 0);
        assertTrue(provider.supports(mp, null));
    }

    @Test
    void supports_requestEntityType_returnsTrue() throws Exception {
        Method method = getClass().getMethod("requestEntityParam", RequestEntity.class);
        MethodParameter mp = new MethodParameter(method, 0);
        assertTrue(provider.supports(mp, null));
    }

    @Test
    void supports_nonEntityType_returnsFalse() throws Exception {
        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        assertFalse(provider.supports(mp, null));
    }

    @Test
    void getResolver_returnsHttpEntityArgResolver() throws Exception {
        Method method = getClass().getMethod("entityParam", HttpEntity.class);
        MethodParameter mp = new MethodParameter(method, 0);
        // webContext mock returns null for getWebComponent by default -> constructor doesn't NPE
        doReturn(null).when(webContext).getWebComponent(HttpBodyCodecRegistry.class);

        StaticArgumentResolver resolver = provider.getResolver(mp, null, webContext);
        assertInstanceOf(HttpEntityArgResolver.class, resolver);
    }

    @SuppressWarnings("unused")
    public void entityParam(HttpEntity<String> entity) {}

    @SuppressWarnings("unused")
    public void requestEntityParam(RequestEntity<String> entity) {}

    @SuppressWarnings("unused")
    public void stringParam(String s) {}
}

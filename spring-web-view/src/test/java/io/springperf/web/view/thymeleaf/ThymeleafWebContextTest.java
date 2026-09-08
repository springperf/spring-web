package io.springperf.web.view.thymeleaf;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.IContext;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ThymeleafWebContextTest {

    private final WebServerHttpRequest req = mock(WebServerHttpRequest.class);
    private final WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

    @Test
    void implementsIContext() {
        ThymeleafWebContext ctx = new ThymeleafWebContext(new HashMap<>(), Locale.US, req, resp);
        assertTrue(ctx instanceof IContext, "Thymeleaf 3.0 下应实现非 web 的 IContext");
    }

    @Test
    void getLocale_usesProvidedLocale() {
        ThymeleafWebContext ctx = new ThymeleafWebContext(new HashMap<>(), Locale.US, req, resp);
        assertEquals(Locale.US, ctx.getLocale());
    }

    @Test
    void getLocale_defaultsWhenNull() {
        ThymeleafWebContext ctx = new ThymeleafWebContext(new HashMap<>(), null, req, resp);
        assertEquals(Locale.getDefault(), ctx.getLocale());
    }

    @Test
    void variables_modelBased() {
        Map<String, Object> model = new HashMap<>();
        model.put("name", "Perf");
        ThymeleafWebContext ctx = new ThymeleafWebContext(model, Locale.US, req, resp);
        assertTrue(ctx.containsVariable("name"));
        assertFalse(ctx.containsVariable("missing"));
        assertEquals("Perf", ctx.getVariable("name"));
        assertTrue(ctx.getVariableNames().contains("name"));
        assertEquals(1, ctx.getVariableNames().size());
    }

    @Test
    void emptyModel_returnsNoVariables() {
        ThymeleafWebContext ctx = new ThymeleafWebContext(null, Locale.US, req, resp);
        assertFalse(ctx.containsVariable("anything"));
        assertNull(ctx.getVariable("anything"));
        assertTrue(ctx.getVariableNames().isEmpty());
    }
}

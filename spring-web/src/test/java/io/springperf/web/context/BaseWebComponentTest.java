package io.springperf.web.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class BaseWebComponentTest {

    @Test
    void initWithWebContext_setsWebContext() {
        BaseWebComponent component = new BaseWebComponent() {};
        WebContext webContext = mock(WebContext.class);
        component.initWithWebContext(webContext);
        assertSame(webContext, component.getWebContext());
    }

    @Test
    void getWebContext_beforeInit_returnsNull() {
        BaseWebComponent component = new BaseWebComponent() {};
        assertNull(component.getWebContext());
    }

    @Test
    void initWithWebContext_overwritesPrevious() {
        BaseWebComponent component = new BaseWebComponent() {};
        WebContext ctx1 = mock(WebContext.class);
        WebContext ctx2 = mock(WebContext.class);
        component.initWithWebContext(ctx1);
        component.initWithWebContext(ctx2);
        assertSame(ctx2, component.getWebContext());
    }
}
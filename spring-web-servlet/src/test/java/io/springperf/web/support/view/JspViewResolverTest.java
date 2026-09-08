package io.springperf.web.support.view;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.support.servlet.context.PerfServletContext;
import io.springperf.web.view.View;
import org.apache.jasper.compiler.TldCache;
import org.apache.jasper.runtime.JspFactoryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.servlet.jsp.JspFactory;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JspViewResolverTest {

    @Mock WebContext webContext;
    @Mock MappingRegistry mappingRegistry;
    @Mock WebServerHttpRequest req;

    private String viewPath(View view) {
        return (String) org.springframework.test.util.ReflectionTestUtils.getField(view, "path");
    }

    @Test
    void defaultConstructor_usesJspPrefixAndSuffix() {
        JspViewResolver resolver = new JspViewResolver();
        View view = resolver.resolveViewName("jsp:index", Locale.US, req);
        assertNotNull(view);
        assertInstanceOf(JspView.class, view);
        assertEquals("/jsp/index.jsp", viewPath(view));
    }

    @Test
    void customPrefixAndSuffix() {
        JspViewResolver resolver = new JspViewResolver("/templates/", ".jspx");
        assertEquals("/templates/page.jspx", viewPath(resolver.resolveViewName("jsp:page", Locale.US, req)));
    }

    @Test
    void resolveViewName_null_returnsNull() {
        assertNull(new JspViewResolver().resolveViewName(null, Locale.US, req));
    }

    @Test
    void resolveViewName_nonJspName_returnsNull() {
        assertNull(new JspViewResolver().resolveViewName("hello", Locale.US, req));
    }

    @Test
    void resolveViewName_suffixForm_mapsToPrefix() {
        JspViewResolver resolver = new JspViewResolver();
        View view = resolver.resolveViewName("hello.jsp", Locale.US, req);
        assertNotNull(view);
        assertEquals("/jsp/hello.jsp", viewPath(view));
    }

    @Test
    void initComponentPhase1_registersJspServletRoute() throws Exception {
        JspFactory.setDefaultFactory(null);
        io.springperf.web.context.ApplicationProperties props = mock(io.springperf.web.context.ApplicationProperties.class);
        lenient().when(props.get(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(webContext.getProps()).thenReturn(props);
        PerfServletContext servletContext = new PerfServletContext(webContext);
        when(webContext.getWebComponent(MappingRegistry.class)).thenReturn(mappingRegistry);
        when(webContext.getWebComponent(PerfServletContext.class)).thenReturn(servletContext);

        JspViewResolver resolver = new JspViewResolver();
        resolver.initWithWebContext(webContext);
        resolver.initComponentPhase1();

        verify(mappingRegistry).registerMapping(argThat(ctx ->
                ctx.getPathRule().equals("/**/*.jsp")));
        assertNotNull(JspFactory.getDefaultFactory(), "鍒濆鍖栧悗搴旇缃?JspFactory");
        assertInstanceOf(JspFactoryImpl.class, JspFactory.getDefaultFactory());
        assertNotNull(TldCache.getInstance(servletContext), "鍒濆鍖栧悗搴旇缃?TldCache");
    }

    @Test
    void initComponentPhase1_withoutMappingRegistry_skipsGracefully() {
        when(webContext.getWebComponent(MappingRegistry.class)).thenReturn(null);
        JspViewResolver resolver = new JspViewResolver();
        resolver.initWithWebContext(webContext);
        assertDoesNotThrow(() -> resolver.initComponentPhase1());
    }
}

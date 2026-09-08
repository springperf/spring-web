package io.springperf.web.support.servlet.filter;

import io.springperf.web.context.WebContext;
import io.springperf.web.support.servlet.context.PerfServletContext;
import javax.servlet.Filter;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebFilter;
import javax.servlet.annotation.WebInitParam;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验证 {@link FilterWrapper} 的 init-param 解析与初始化：
 * @WebFilter initParams 解析、无注解兜底空 Map、filter.init 调用与 ServletException 包装。
 */
class FilterWrapperInitParamsTest {

    @WebFilter(urlPatterns = "/api/*", initParams = {
            @WebInitParam(name = "mode", value = "strict"),
            @WebInitParam(name = "retry", value = "3")
    })
    static class AnnotatedFilter implements Filter {
        public void doFilter(javax.servlet.ServletRequest req, javax.servlet.ServletResponse resp,
                             javax.servlet.FilterChain chain) {}
        public void init(FilterConfig config) {}
        public void destroy() {}
    }

    @WebFilter
    static class NoParamFilter implements Filter {
        public void doFilter(javax.servlet.ServletRequest req, javax.servlet.ServletResponse resp,
                             javax.servlet.FilterChain chain) {}
        public void init(FilterConfig config) {}
        public void destroy() {}
    }

    static class PlainFilter implements Filter {
        public void doFilter(javax.servlet.ServletRequest req, javax.servlet.ServletResponse resp,
                             javax.servlet.FilterChain chain) {}
        public void init(FilterConfig config) {}
        public void destroy() {}
    }

    @Test
    void resolveInitParams_withWebInitParams_returnsMap() {
        FilterWrapper wrapper = new FilterWrapper(new AnnotatedFilter());
        Map<String, String> params = wrapper.resolveInitParams();
        assertEquals(2, params.size());
        assertEquals("strict", params.get("mode"));
        assertEquals("3", params.get("retry"));
    }

    @Test
    void resolveInitParams_withWebFilterNoParams_empty() {
        FilterWrapper wrapper = new FilterWrapper(new NoParamFilter());
        assertTrue(wrapper.resolveInitParams().isEmpty());
    }

    @Test
    void resolveInitParams_plainFilter_empty() {
        FilterWrapper wrapper = new FilterWrapper(new PlainFilter());
        assertTrue(wrapper.resolveInitParams().isEmpty());
    }

    @Test
    void initWithWebContext_initsFilterOnce_withPerfServletContext() throws Exception {
        Filter filter = spy(new PlainFilter());
        FilterWrapper wrapper = new FilterWrapper(filter, 5);
        WebContext webContext = mock(WebContext.class);
        PerfServletContext servletContext = mock(PerfServletContext.class);
        when(webContext.getWebComponent(PerfServletContext.class)).thenReturn(servletContext);

        wrapper.initWithWebContext(webContext);
        wrapper.initWithWebContext(webContext);

        verify(filter, times(1)).init(any(FilterConfig.class));
        assertEquals(5, wrapper.getOrder());
    }

    @Test
    void initWithWebContext_filterInitFails_wrapsServletException() throws Exception {
        Filter filter = new Filter() {
            public void doFilter(javax.servlet.ServletRequest req, javax.servlet.ServletResponse resp,
                                 javax.servlet.FilterChain chain) {}
            public void init(FilterConfig config) throws ServletException {
                throw new ServletException("init fail");
            }
            public void destroy() {}
        };
        FilterWrapper wrapper = new FilterWrapper(filter);
        WebContext webContext = mock(WebContext.class);
        when(webContext.getWebComponent(PerfServletContext.class)).thenReturn(mock(PerfServletContext.class));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> wrapper.initWithWebContext(webContext));
        assertTrue(ex.getMessage().contains("Failed to init filter"),
                "异常应携带 filter 初始化失败上下文，实际: " + ex.getMessage());
        assertNotNull(ex.getCause(), "应包装原始 ServletException");
        assertInstanceOf(ServletException.class, ex.getCause());
    }

    @Test
    void destroyComponent_callsFilterDestroy() throws Exception {
        Filter filter = spy(new PlainFilter());
        FilterWrapper wrapper = new FilterWrapper(filter);
        wrapper.destroyComponent();
        verify(filter).destroy();
    }
}
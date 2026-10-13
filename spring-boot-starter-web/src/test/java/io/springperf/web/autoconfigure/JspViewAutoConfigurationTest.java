package io.springperf.web.autoconfigure;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.support.view.JspViewResolver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code spring.mvc.view.prefix/suffix} 接入验证：{@link JspViewAutoConfiguration} 按配置
 * 构造 {@link JspViewResolver}；未配置/空串回退框架默认。
 */
class JspViewAutoConfigurationTest {

    private static ApplicationProperties props(String prefix, String suffix) {
        ApplicationProperties props = mock(ApplicationProperties.class);
        lenient().when(props.get(PropertiesConstant.MVC_VIEW_PREFIX,
                        PropertiesConstant.MVC_VIEW_PREFIX_DEFAULT))
                .thenReturn(prefix != null ? prefix : PropertiesConstant.MVC_VIEW_PREFIX_DEFAULT);
        lenient().when(props.get(PropertiesConstant.MVC_VIEW_SUFFIX,
                        PropertiesConstant.MVC_VIEW_SUFFIX_DEFAULT))
                .thenReturn(suffix != null ? suffix : PropertiesConstant.MVC_VIEW_SUFFIX_DEFAULT);
        return props;
    }

    private static String field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (String) f.get(target);
    }

    @Test
    void defaults_useFrameworkHistoricalValues() throws Exception {
        JspViewResolver resolver = new JspViewAutoConfiguration()
                .jspViewResolver(props(null, null));
        assertEquals("/jsp/", field(resolver, "prefix"), "未配置应沿用框架默认 /jsp/");
        assertEquals(".jsp", field(resolver, "suffix"));
    }

    @Test
    void customPrefixAndSuffix_applied() throws Exception {
        JspViewResolver resolver = new JspViewAutoConfiguration()
                .jspViewResolver(props("/WEB-INF/views/", ".jspf"));
        assertEquals("/WEB-INF/views/", field(resolver, "prefix"));
        assertEquals(".jspf", field(resolver, "suffix"));
    }

    @Test
    void emptyValues_fallBackToDefaults() throws Exception {
        // 显式空串视为未配置：回退框架默认
        ApplicationProperties p = props("", "");
        when(p.get(PropertiesConstant.MVC_VIEW_PREFIX, PropertiesConstant.MVC_VIEW_PREFIX_DEFAULT))
                .thenReturn("");
        when(p.get(PropertiesConstant.MVC_VIEW_SUFFIX, PropertiesConstant.MVC_VIEW_SUFFIX_DEFAULT))
                .thenReturn("");
        JspViewResolver resolver = new JspViewAutoConfiguration().jspViewResolver(p);
        assertEquals("/jsp/", field(resolver, "prefix"));
        assertEquals(".jsp", field(resolver, "suffix"));
    }
}

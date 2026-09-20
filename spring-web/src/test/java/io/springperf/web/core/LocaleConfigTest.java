package io.springperf.web.core;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContext;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LocaleConfig} 单元测试：{@code spring.web.locale} / {@code spring.web.locale-resolver} 解析与请求 Locale 绑定。
 */
class LocaleConfigTest {

    private static ApplicationProperties props(String locale, String resolver) {
        ApplicationProperties props = mock(ApplicationProperties.class);
        lenient().when(props.get(PropertiesConstant.WEB_LOCALE, null)).thenReturn(locale);
        // 模拟真实 get(key, default) 语义：未显式给 resolver 时返回默认值 accept-header
        lenient().when(props.get(PropertiesConstant.WEB_LOCALE_RESOLVER,
                        PropertiesConstant.WEB_LOCALE_RESOLVER_DEFAULT))
                .thenReturn(resolver != null ? resolver : PropertiesConstant.WEB_LOCALE_RESOLVER_DEFAULT);
        lenient().when(props.getBoolean(PropertiesConstant.WEB_LOCALE_BIND,
                        PropertiesConstant.WEB_LOCALE_BIND_DEFAULT))
                .thenReturn(PropertiesConstant.WEB_LOCALE_BIND_DEFAULT);
        return props;
    }

    private static ApplicationProperties propsBindDisabled() {
        ApplicationProperties props = props(null, null);
        when(props.getBoolean(PropertiesConstant.WEB_LOCALE_BIND,
                PropertiesConstant.WEB_LOCALE_BIND_DEFAULT)).thenReturn(false);
        return props;
    }

    private static WebServerHttpRequest request(Locale locale) {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        lenient().when(req.getLocale()).thenReturn(locale);
        return req;
    }

    // ==================== 配置解析 ====================

    @Test
    void parseLocale_underscoreAndHyphen() {
        assertEquals(new Locale("zh", "CN"), LocaleConfig.parseLocale("zh_CN"));
        assertEquals(new Locale("zh", "CN"), LocaleConfig.parseLocale("zh-CN"));
        assertEquals(new Locale("en"), LocaleConfig.parseLocale("en"));
        assertEquals(new Locale("zh", "CN", "Hans"), LocaleConfig.parseLocale("zh_CN_Hans"));
    }

    @Test
    void parseLocale_blank_returnsNull() {
        assertEquals(null, LocaleConfig.parseLocale(null));
        assertEquals(null, LocaleConfig.parseLocale("  "));
    }

    @Test
    void fromProperties_default_isAcceptHeader() {
        LocaleConfig cfg = LocaleConfig.fromProperties(props(null, null));
        assertFalse(cfg.isFixed());
        assertEquals("accept-header", cfg.getResolver());
        assertEquals(null, cfg.getLocale());
    }

    @Test
    void fromProperties_fixed_parsesLocale() {
        LocaleConfig cfg = LocaleConfig.fromProperties(props("zh_CN", "fixed"));
        assertTrue(cfg.isFixed());
        assertEquals(new Locale("zh", "CN"), cfg.getLocale());
    }

    @Test
    void fromProperties_resolverCaseInsensitive() {
        assertTrue(LocaleConfig.fromProperties(props("en", "FIXED")).isFixed());
    }

    // ==================== 请求解析 ====================

    @Test
    void resolveLocaleContext_fixed_usesConfiguredLocale() {
        LocaleConfig cfg = LocaleConfig.fromProperties(props("zh_CN", "fixed"));
        LocaleContext ctx = cfg.resolveLocaleContext(request(Locale.US));
        // fixed 策略忽略请求头，恒用配置 Locale
        assertEquals(new Locale("zh", "CN"), ctx.getLocale());
    }

    @Test
    void resolveLocaleContext_fixed_noConfiguredLocale_fallsBackToJvmDefault() {
        LocaleConfig cfg = LocaleConfig.fromProperties(props(null, "fixed"));
        LocaleContext ctx = cfg.resolveLocaleContext(request(Locale.US));
        assertEquals(Locale.getDefault(), ctx.getLocale());
    }

    @Test
    void resolveLocaleContext_acceptHeader_usesRequestLocale() {
        LocaleConfig cfg = LocaleConfig.fromProperties(props(null, "accept-header"));
        LocaleContext ctx = cfg.resolveLocaleContext(request(Locale.GERMANY));
        assertEquals(Locale.GERMANY, ctx.getLocale());
    }

    @Test
    void resolveLocaleContext_acceptHeader_noRequestLocale_fallsBackToConfigured() {
        LocaleConfig cfg = LocaleConfig.fromProperties(props("fr", "accept-header"));
        LocaleContext ctx = cfg.resolveLocaleContext(request(null));
        assertEquals(new Locale("fr"), ctx.getLocale());
    }

    @Test
    void resolveLocaleContext_nullRequest_doesNotThrow() {
        LocaleConfig cfg = LocaleConfig.fromProperties(props("en", "accept-header"));
        LocaleContext ctx = cfg.resolveLocaleContext(null);
        assertEquals(new Locale("en"), ctx.getLocale());
    }

    // ==================== 优化项：绑定开关 / 懒解析 / 单例复用 ====================

    @Test
    void fromProperties_bindDisabled() {
        LocaleConfig cfg = LocaleConfig.fromProperties(propsBindDisabled());
        assertFalse(cfg.isBindEnabled(), "spring.web.locale-bind=false 应关闭每请求绑定");
        assertTrue(LocaleConfig.fromProperties(props(null, null)).isBindEnabled(), "默认启用绑定");
    }

    @Test
    void resolveLocaleContext_acceptHeader_isLazy() {
        LocaleConfig cfg = LocaleConfig.fromProperties(props(null, "accept-header"));
        WebServerHttpRequest req = request(Locale.GERMANY);

        LocaleContext ctx = cfg.resolveLocaleContext(req);
        verify(req, never()).getLocale();

        assertEquals(Locale.GERMANY, ctx.getLocale());
        verify(req, times(1)).getLocale();

        assertEquals(Locale.GERMANY, ctx.getLocale());
        verify(req, times(1)).getLocale();  // 结果缓存，不重复解析
    }

    @Test
    void resolveLocaleContext_fixedConfiguredLocale_reusesSingletonContext() {
        LocaleConfig cfg = LocaleConfig.fromProperties(props("zh_CN", "fixed"));
        LocaleContext first = cfg.resolveLocaleContext(request(null));
        LocaleContext second = cfg.resolveLocaleContext(request(Locale.US));
        assertSame(first, second, "fixed 策略与请求无关，应复用预建单例（零分配）");
        assertEquals(new Locale("zh", "CN"), first.getLocale());
    }

    @Test
    void resolveLocaleContext_fixedNoConfiguredLocale_reusesDefaultContext() {
        LocaleConfig cfg = LocaleConfig.fromProperties(props(null, "fixed"));
        assertSame(cfg.resolveLocaleContext(request(null)), cfg.resolveLocaleContext(request(null)),
                "未配置 locale 时按 JVM 默认，常量上下文可复用（随 setDefault 重建）");
    }
}

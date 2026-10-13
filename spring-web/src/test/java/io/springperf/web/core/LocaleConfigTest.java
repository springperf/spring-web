package io.springperf.web.core;

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

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.http.HttpHeaders;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.http.WebServerHttpRequest;

/**
 * {@link LocaleConfig} 单元测试：{@code spring.web.locale} / {@code spring.web.locale-resolver} 解析与请求 Locale 绑定。
 */
class LocaleConfigTest {

    private static ApplicationProperties props(String locale, String resolver) {
        ApplicationProperties props = mock(ApplicationProperties.class);
        lenient().when(props.get(PropertiesConstant.WEB_LOCALE, null)).thenReturn(locale);
        // 模拟真实 get(key, default) 语义：未显式给 resolver 时返回默认值 accept-header
        lenient()
                .when(props.get(PropertiesConstant.WEB_LOCALE_RESOLVER, PropertiesConstant.WEB_LOCALE_RESOLVER_DEFAULT))
                .thenReturn(resolver != null ? resolver : PropertiesConstant.WEB_LOCALE_RESOLVER_DEFAULT);
        lenient().when(props.getBoolean(PropertiesConstant.WEB_LOCALE_BIND, PropertiesConstant.WEB_LOCALE_BIND_DEFAULT))
                .thenReturn(PropertiesConstant.WEB_LOCALE_BIND_DEFAULT);
        return props;
    }

    private static ApplicationProperties propsBindDisabled() {
        ApplicationProperties props = props(null, null);
        when(props.getBoolean(PropertiesConstant.WEB_LOCALE_BIND, PropertiesConstant.WEB_LOCALE_BIND_DEFAULT))
                .thenReturn(false);
        return props;
    }

    /**
     * JVM 默认 Locale 快照必须整体一致：{@code setDefault()} 之后取到的上下文,其 locale 必须等于当时的默认 Locale。
     * <p>
     * 回归用例：早前 locale 与 context 分处两个 {@code volatile}，并发 {@code setDefault()} 下可能读到「新 locale + 旧 context」的自洽组合，从
     * 而返回上一个默认 Locale 的上下文。现在两者装进同一不可变快照、单引用发布。
     * </p>
     */
    @Test
    void defaultLocaleContextStaysConsistentWithLocaleSetDefault() {
        Locale original = Locale.getDefault();
        try {
            // fixed 策略 + 未配置 locale → 走 defaultLocaleContext() 快照路径
            LocaleConfig fixedWithoutLocale = new LocaleConfig(null, "fixed", true);

            Locale first = Locale.forLanguageTag("en-GB");
            Locale.setDefault(first);
            assertEquals(first, fixedWithoutLocale.resolveLocaleContext(null).getLocale());

            Locale second = Locale.forLanguageTag("fr-CA");
            Locale.setDefault(second);
            assertEquals(second, fixedWithoutLocale.resolveLocaleContext(null).getLocale());

            // 换回第一个：快照必须重新发布，而不是沿用上一次的 context
            Locale.setDefault(first);
            assertEquals(first, fixedWithoutLocale.resolveLocaleContext(null).getLocale());
        } finally {
            Locale.setDefault(original);
        }
    }

    private WebServerHttpRequest request(Locale locale) {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        if (locale != null) {
            // 真实客户端带 Accept-Language 时才会有首选 Locale
            headers.set("Accept-Language", locale.toLanguageTag());
        }
        lenient().when(req.getHeaders()).thenReturn(headers);
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

    @Test
    void resolveLocaleContext_acceptHeader_noHeader_fallsBackToConfigured_evenWhenRequestLocaleIsJvmDefault() {
        // 真实请求在无 Accept-Language 时会回退到 JVM 默认（BaseWebServerHttpRequest#defaultLocaleList）；
        // 这里用 en_US 模拟 CI runner 的默认语言：配置的 zh_CN 必须胜过它（回归用例，对应 CI 的
        // LocaleAcceptHeaderE2eTest 失败）。
        LocaleConfig cfg = LocaleConfig.fromProperties(props("zh_CN", "accept-header"));
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        lenient().when(req.getHeaders()).thenReturn(new HttpHeaders());
        lenient().when(req.getLocale()).thenReturn(Locale.US);
        assertEquals(new Locale("zh", "CN"), cfg.resolveLocaleContext(req).getLocale());
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
        verify(req, times(1)).getLocale(); // 结果缓存，不重复解析
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

package io.springperf.web.core;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.http.WebServerHttpRequest;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.SimpleLocaleContext;

import java.util.Locale;

/**
 * Locale 解析配置（对齐 {@code spring.web.locale} / {@code spring.web.locale-resolver}）。
 * 启动期预解析一次。
 *
 * <ul>
 *   <li>{@code locale}：默认 Locale（如 {@code zh_CN}），可空。</li>
 *   <li>{@code locale-resolver}：{@code fixed}（恒用配置的 locale）/ {@code accept-header}
 *       （按请求 Accept-Language，默认），对齐 Boot 的固定 / 请求头两种策略。</li>
 *   <li>{@code locale-bind}：是否每请求绑定 {@code LocaleContextHolder}（默认 true）。
 *       关闭时不产生任何每请求 locale 开销。</li>
 * </ul>
 *
 * <p><b>每请求成本</b>：解析结果按策略分层缓存——fixed 命中预建单例（零分配）；
 * accept-header 返回懒上下文（首次 {@code getLocale()} 才解析请求头并缓存结果），
 * 多数请求不读 Locale 即零解析成本。</p>
 */
public class LocaleConfig {

    /** 默认：accept-header 策略，无固定 Locale，绑定启用。 */
    public static final LocaleConfig DEFAULT = new LocaleConfig(null, "accept-header", false);

    private final Locale locale;
    private final String resolver;
    private final boolean fixed;
    private final boolean bindEnabled;
    /** fixed 且配置了 locale 时的预建单例上下文（每请求直接复用，零分配）。 */
    private final LocaleContext fixedContext;

    public LocaleConfig(Locale locale, String resolver, boolean fixed) {
        this(locale, resolver, fixed, true);
    }

    public LocaleConfig(Locale locale, String resolver, boolean fixed, boolean bindEnabled) {
        this.locale = locale;
        this.resolver = resolver;
        this.fixed = fixed;
        this.bindEnabled = bindEnabled;
        this.fixedContext = locale != null ? new SimpleLocaleContext(locale) : null;
    }

    public static LocaleConfig fromProperties(ApplicationProperties props) {
        String localeStr = props.get(PropertiesConstant.WEB_LOCALE, null);
        String resolver = props.get(PropertiesConstant.WEB_LOCALE_RESOLVER,
                PropertiesConstant.WEB_LOCALE_RESOLVER_DEFAULT);
        Locale locale = parseLocale(localeStr);
        boolean fixed = "fixed".equalsIgnoreCase(resolver != null ? resolver.trim() : "");
        boolean bindEnabled = props.getBoolean(PropertiesConstant.WEB_LOCALE_BIND,
                PropertiesConstant.WEB_LOCALE_BIND_DEFAULT);
        return new LocaleConfig(locale, resolver, fixed, bindEnabled);
    }

    /** 解析 {@code zh_CN} / {@code en} 等 Locale 字符串（下划线或连字符分隔）。非法返回 null。 */
    static Locale parseLocale(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String[] parts = value.trim().split("[_-]");
        try {
            if (parts.length == 1) {
                return new Locale(parts[0]);
            }
            if (parts.length == 2) {
                return new Locale(parts[0], parts[1]);
            }
            return new Locale(parts[0], parts[1], parts[2]);
        } catch (Exception e) {
            return null;
        }
    }

    public Locale getLocale() {
        return locale;
    }

    public boolean isFixed() {
        return fixed;
    }

    public String getResolver() {
        return resolver;
    }

    /** 是否每请求绑定 {@code LocaleContextHolder}（{@code spring.web.locale-bind}）。 */
    public boolean isBindEnabled() {
        return bindEnabled;
    }

    /**
     * 解析当前请求的 {@link LocaleContext}：fixed 策略返回配置的固定 Locale
     * （未配置则 JVM 默认）；否则返回请求首选 Locale（Accept-Language）。
     * 供 DispatcherHandler 绑定到 {@code LocaleContextHolder}。
     *
     * <p>fixed + 配置了 locale → 预建单例（零分配）；fixed 无配置 → JVM 默认上下文
     * （随 {@code Locale.setDefault()} 变化重建）；accept-header → 懒上下文
     * （首次 {@code getLocale()} 才读请求头，结果缓存）。</p>
     */
    public LocaleContext resolveLocaleContext(WebServerHttpRequest request) {
        if (fixed) {
            return fixedContext != null ? fixedContext : defaultLocaleContext();
        }
        return new LazyRequestLocaleContext(this, request);
    }

    /** accept-header 策略的解析：请求首选 Locale，缺失则回退配置 locale，再回退 JVM 默认。 */
    Locale resolveRequestLocale(WebServerHttpRequest request) {
        Locale requestLocale = request != null ? request.getLocale() : null;
        return requestLocale != null ? requestLocale : (locale != null ? locale : Locale.getDefault());
    }

    // ---- JVM 默认 Locale 常量上下文（随 setDefault 变化重建，稳态零分配） ----

    private static volatile Locale cachedDefaultLocale;
    private static volatile LocaleContext cachedDefaultContext;

    static LocaleContext defaultLocaleContext() {
        Locale current = Locale.getDefault();
        LocaleContext ctx = cachedDefaultContext;
        if (ctx == null || cachedDefaultLocale != current) {
            ctx = new SimpleLocaleContext(current);
            cachedDefaultLocale = current;
            cachedDefaultContext = ctx;
        }
        return ctx;
    }

    /**
     * 懒解析的请求 Locale 上下文：{@link #getLocale()} 首次调用才解析
     * （读 Accept-Language + 缓存查找），随后缓存结果。
     *
     * <p>多数请求（API/静态资源/健康检查）从不读取 Locale——据此把解析从
     * 「每请求必做」降为「实际读取才做」。</p>
     */
    static final class LazyRequestLocaleContext implements LocaleContext {

        private final LocaleConfig config;
        private final WebServerHttpRequest request;
        private volatile Locale resolved;

        LazyRequestLocaleContext(LocaleConfig config, WebServerHttpRequest request) {
            this.config = config;
            this.request = request;
        }

        @Override
        public Locale getLocale() {
            Locale value = resolved;
            if (value == null) {
                value = config.resolveRequestLocale(request);
                resolved = value;
            }
            return value;
        }
    }
}

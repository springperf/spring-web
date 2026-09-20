package io.springperf.web.support.servlet.session;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.support.servlet.Authenticator;
import io.springperf.web.support.servlet.context.PerfServletContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
public class PerfHttpSessionManager extends BaseWebComponent {

    public static final RequestAttribute<PerfHttpSession> SESSION_ATTR_KEY =
            RequestAttribute.createAttribute(PerfHttpSession.class);

    // ---- Cookie 配置键 ----
    static final String COOKIE_NAME_KEY = "server.servlet.session.cookie.name";
    static final String COOKIE_SAME_SITE_KEY = "server.servlet.session.cookie.same-site";
    static final String COOKIE_SECURE_KEY = "server.servlet.session.cookie.secure";
    static final String COOKIE_DOMAIN_KEY = "server.servlet.session.cookie.domain";
    static final String COOKIE_MAX_AGE_KEY = "server.servlet.session.cookie.max-age";
    static final String COOKIE_HTTP_ONLY_KEY = "server.servlet.session.cookie.http-only";

    public static final String DEFAULT_SESSION_COOKIE_NAME = "JSESSIONID";
    static final String REQUESTED_SESSION_ID_ATTR = PerfHttpSessionManager.class.getName() + ".REQUESTED_SESSION_ID";

    /** Session 属性名，用于存储当前已认证的 {@link java.security.Principal}。 */
    public static final String PRINCIPAL_KEY = PerfHttpSessionManager.class.getName() + ".PRINCIPAL";

    private HttpSessionStorage storage;
    private ServletContext servletContext;
    private Authenticator authenticator;
    private List<HttpSessionListener> sessionListeners = Collections.emptyList();
    private List<HttpSessionAttributeListener> attributeListeners = Collections.emptyList();

    // ---- Cookie 配置 ----
    private String cookieName = DEFAULT_SESSION_COOKIE_NAME;
    private String cookiePath = "/";
    private String sameSite;
    private boolean cookieSecure;
    private String cookieDomain;
    private int cookieMaxAge = -1;
    private boolean cookieHttpOnly = true;

    /**
     * SameSite 配置值规范化为 Netty {@code CookieHeaderNames.SameSite} 枚举的精确常量名
     * （Lax/Strict/None——注意非全大写）：配置大小写不敏感，未知值忽略并告警。
     */
    static String canonicalSameSite(String raw) {
        switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "lax": return "Lax";
            case "strict": return "Strict";
            case "none": return "None";
            default:
                log.warn("Unknown server.servlet.session.cookie.same-site value: {}, ignored", raw);
                return null;
        }
    }

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        // ServletContext 由 auto-config 作为独立组件注册（必然存在）；
        // 此处直接引用。兜底：未注册时创建并注册（如脱离 auto-config 单独使用）。
        PerfServletContext servletCtx = webContext.getWebComponent(PerfServletContext.class);
        if (servletCtx == null) {
            servletCtx = new PerfServletContext(webContext);
            webContext.registerWebComponent(servletCtx);
        }
        this.servletContext = servletCtx;
        HttpSessionStorage bean = webContext.getBeanFromCtx(HttpSessionStorage.class);
        this.storage = bean != null ? bean : createDefaultStorage(webContext);
        // Scan for Authenticator bean
        this.authenticator = webContext.getBeanFromCtx(Authenticator.class);
        // Scan for HttpSessionListener and HttpSessionAttributeListener beans
        this.sessionListeners = new ArrayList<>(
                webContext.getCtx().getBeansOfType(HttpSessionListener.class).values());
        this.attributeListeners = new ArrayList<>(
                webContext.getCtx().getBeansOfType(HttpSessionAttributeListener.class).values());

        // Read cookie configuration
        this.cookieName = webContext.getProps().get(COOKIE_NAME_KEY, DEFAULT_SESSION_COOKIE_NAME);
        this.cookieSecure = webContext.getProps().getBoolean(COOKIE_SECURE_KEY, false);
        String configuredSameSite = webContext.getProps().get(COOKIE_SAME_SITE_KEY, "");
        this.sameSite = configuredSameSite.isEmpty() ? null : canonicalSameSite(configuredSameSite);
        this.cookieDomain = webContext.getProps().get(COOKIE_DOMAIN_KEY, null);
        String maxAgeRaw = webContext.getProps().get(COOKIE_MAX_AGE_KEY, null);
        this.cookieMaxAge = -1;
        if (maxAgeRaw != null && !maxAgeRaw.trim().isEmpty()) {
            try {
                this.cookieMaxAge = Integer.parseInt(maxAgeRaw.trim());
            } catch (NumberFormatException e) {
                this.cookieMaxAge = -1;
            }
        }
        this.cookieHttpOnly = webContext.getProps().getBoolean(COOKIE_HTTP_ONLY_KEY, true);
        String ctxPath = webContext.getContextPath();
        this.cookiePath = (ctxPath == null || ctxPath.isEmpty() || "/".equals(ctxPath)) ? "/" : ctxPath;
    }

    /**
     * 按 {@code server.servlet.session.persistent} 选择默认存储：
     * {@code true} → {@link FileHttpSessionStorage}（每 session 一文件，重启恢复）；
     * {@code false}（默认）→ 现有 {@link InMemoryHttpSessionStorage}。
     * 容器中存在自定义 {@link HttpSessionStorage} bean 时优先使用该 bean（不走此方法）。
     */
    private HttpSessionStorage createDefaultStorage(WebContext webContext) {
        boolean persistent = webContext.getProps().getBoolean(
                PropertiesConstant.SERVLET_SESSION_PERSISTENT,
                PropertiesConstant.SERVLET_SESSION_PERSISTENT_DEFAULT);
        if (!persistent) {
            return new InMemoryHttpSessionStorage();
        }
        String storeDir = webContext.getProps().get(PropertiesConstant.SERVLET_SESSION_STORE_DIR,
                PropertiesConstant.SERVLET_SESSION_STORE_DIR_DEFAULT);
        String excludeRaw = webContext.getProps().get(PropertiesConstant.SERVLET_SESSION_PERSISTENT_EXCLUDE, null);
        java.util.Set<String> exclude = FileHttpSessionStorage.parseExcludeList(excludeRaw);
        java.nio.file.Path dir = java.nio.file.Paths.get(storeDir);
        log.info("Session persistence enabled (server.servlet.session.persistent=true), store-dir={}, exclude={}",
                dir.toAbsolutePath(), exclude);
        return new FileHttpSessionStorage(dir, exclude);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10000;
    }

    public ServletContext getServletContext() {
        return servletContext;
    }

    public Authenticator getAuthenticator() {
        return authenticator;
    }

    public HttpSessionStorage getStorage() {
        return storage;
    }

    public List<HttpSessionListener> getSessionListeners() {
        return sessionListeners;
    }

    public List<HttpSessionAttributeListener> getAttributeListeners() {
        return attributeListeners;
    }

    public String getCookieName() {
        return cookieName;
    }

    public String getCookiePath() {
        return cookiePath;
    }

    public String getSameSite() {
        return sameSite;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public String getCookieDomain() {
        return cookieDomain;
    }

    public int getCookieMaxAge() {
        return cookieMaxAge;
    }

    public boolean isCookieHttpOnly() {
        return cookieHttpOnly;
    }

    public PerfHttpSession getSession(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        HttpSessionData data = storage.getSession(sessionId);
        if (data == null) {
            return null;
        }
        PerfHttpSession session = new PerfHttpSession(data, servletContext, sessionListeners, attributeListeners);
        session.setNotNew();
        session.setOnInvalidateCallback(() -> storage.removeSession(sessionId));
        return session;
    }

    public PerfHttpSession createSession() {
        HttpSessionData data = storage.createSession();
        // 应用 server.servlet.session.timeout：ServletContext.getSessionTimeout() 返回分钟，
        // HttpSessionData.maxInactiveInterval 以秒为单位（isExpired 依赖它做过期清理）。
        // 修复前 maxInactiveInterval 恒为 0，isExpired() 恒 false，in-memory session 永不过期 → 无界内存增长。
        int sessionTimeout = servletContext.getSessionTimeout();
        if (sessionTimeout >= 0) {
            data.setMaxInactiveInterval(sessionTimeout * 60);
        }
        PerfHttpSession session = new PerfHttpSession(data, servletContext, sessionListeners, attributeListeners);
        session.setOnInvalidateCallback(() -> storage.removeSession(data.getId()));
        // Fire sessionCreated event
        if (!sessionListeners.isEmpty()) {
            HttpSessionEvent event = new HttpSessionEvent(session);
            for (HttpSessionListener listener : sessionListeners) {
                listener.sessionCreated(event);
            }
        }
        return session;
    }

    public void removeSession(String sessionId) {
        storage.removeSession(sessionId);
    }

    public void saveSession(PerfHttpSession session) {
        // L8：已失效会话不持久化，避免被并发在途请求复活（invalidate 已从 storage 移除，
        // 若此处仍保存会把已失效会话重新写入存储）。
        if (session.isInvalid()) {
            return;
        }
        storage.saveSession(session.getData());
    }

    @Override
    public void destroyComponent() throws Exception {
        storage.shutdown();
        super.destroyComponent();
    }

    public PerfHttpSession changeSessionId(PerfHttpSession oldSession) {
        String oldId = oldSession.getData().getId();
        // Create new session data with new ID but same attributes
        HttpSessionData newData = storage.createSession();
        HttpSessionData oldData = oldSession.getData();
        for (Map.Entry<String, Object> entry : oldData.getAttributes().entrySet()) {
            newData.setAttribute(entry.getKey(), entry.getValue());
        }
        newData.setMaxInactiveInterval(oldData.getMaxInactiveInterval());
        storage.removeSession(oldId);
        PerfHttpSession newSession = new PerfHttpSession(newData, servletContext, sessionListeners, attributeListeners);
        newSession.setNotNew();
        newSession.setOnInvalidateCallback(() -> storage.removeSession(newData.getId()));
        return newSession;
    }
}
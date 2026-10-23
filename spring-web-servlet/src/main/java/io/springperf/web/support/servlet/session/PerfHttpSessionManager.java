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
import java.io.ObjectInputFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
public class PerfHttpSessionManager extends BaseWebComponent {

    public static final RequestAttribute<PerfHttpSession> SESSION_ATTR_KEY = RequestAttribute
            .createAttribute(PerfHttpSession.class);

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

    /** 会话保存队列容量：阻塞型存储的保存只走这条队列，满则丢弃（不反压请求线程）。 */
    private static final int SAVE_QUEUE_CAPACITY = 4096;

    /** 停机时等待在途保存落盘的上限（秒）。 */
    private static final int SAVE_SHUTDOWN_TIMEOUT_SECONDS = 5;

    /*
     * 以下字段都是「启动期写入一次、运行期只读」的配置快照，但读取方分布在请求线程与
     * 会话保存线程（saveExecutor 的 lambda 捕获了 this）等多个线程 —— 全部 volatile 建立可见性。
     */
    private volatile HttpSessionStorage storage;
    private volatile ServletContext servletContext;
    private volatile Authenticator authenticator;
    private volatile List<HttpSessionListener> sessionListeners = Collections.emptyList();
    private volatile List<HttpSessionAttributeListener> attributeListeners = Collections.emptyList();

    /** 单线程保存执行器：保证同一会话的保存按触发顺序落盘；仅阻塞型存储创建。 */
    private volatile ThreadPoolExecutor saveExecutor;

    /** 队列满告警只打一次，避免过载时刷爆日志。 */
    private final java.util.concurrent.atomic.AtomicBoolean saveQueueFullWarned = new java.util.concurrent.atomic.AtomicBoolean();

    // ---- Cookie 配置 ----
    private volatile String cookieName = DEFAULT_SESSION_COOKIE_NAME;
    private volatile String cookiePath = "/";
    private volatile String sameSite;
    private volatile boolean cookieSecure;
    private volatile String cookieDomain;
    private volatile int cookieMaxAge = -1;
    private volatile boolean cookieHttpOnly = true;

    /**
     * SameSite 配置值规范化为 Netty {@code CookieHeaderNames.SameSite} 枚举的精确常量名 （Lax/Strict/None——注意非全大写）：配置大小写不敏感，未知值忽略并告警。
     */
    static String canonicalSameSite(String raw) {
        switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "lax":
                return "Lax";
            case "strict":
                return "Strict";
            case "none":
                return "None";
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
        // 阻塞型存储（文件/远程）的保存必须离开 EventLoop；内存存储保持同步，省一次线程调度
        shutdownSaveExecutor();
        this.saveExecutor = this.storage.hasBlockingSave() ? createSaveExecutor() : null;
        // Scan for Authenticator bean
        this.authenticator = webContext.getBeanFromCtx(Authenticator.class);
        // Scan for HttpSessionListener and HttpSessionAttributeListener beans
        this.sessionListeners = new ArrayList<>(webContext.getCtx().getBeansOfType(HttpSessionListener.class).values());
        this.attributeListeners = new ArrayList<>(
                webContext.getCtx().getBeansOfType(HttpSessionAttributeListener.class).values());

        // Read cookie configuration
        this.cookieName = webContext.getProps().get(COOKIE_NAME_KEY, DEFAULT_SESSION_COOKIE_NAME);
        this.cookieSecure = webContext.getProps().getBoolean(COOKIE_SECURE_KEY, false);
        String configuredSameSite = webContext.getProps().get(COOKIE_SAME_SITE_KEY, "");
        // get 声明为 @Nullable（此处默认值非空，兜底仅为契约完整）
        this.sameSite = (configuredSameSite == null || configuredSameSite.isEmpty()) ? null
                : canonicalSameSite(configuredSameSite);
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
     * 按 {@code server.servlet.session.persistent} 选择默认存储： {@code true} → {@link FileHttpSessionStorage}（每 session
     * 一文件，重启恢复）； {@code false}（默认）→ 现有 {@link InMemoryHttpSessionStorage}。 容器中存在自定义 {@link HttpSessionStorage} bean
     * 时优先使用该 bean（不走此方法）。
     */
    private HttpSessionStorage createDefaultStorage(WebContext webContext) {
        boolean persistent = webContext.getProps().getBoolean(PropertiesConstant.SERVLET_SESSION_PERSISTENT,
                PropertiesConstant.SERVLET_SESSION_PERSISTENT_DEFAULT);
        if (!persistent) {
            return new InMemoryHttpSessionStorage();
        }
        String storeDir = webContext.getProps().get(PropertiesConstant.SERVLET_SESSION_STORE_DIR,
                PropertiesConstant.SERVLET_SESSION_STORE_DIR_DEFAULT);
        String excludeRaw = webContext.getProps().get(PropertiesConstant.SERVLET_SESSION_PERSISTENT_EXCLUDE, null);
        java.util.Set<String> exclude = FileHttpSessionStorage.parseExcludeList(excludeRaw);
        String filterSpec = webContext.getProps()
                .get(PropertiesConstant.SERVLET_SESSION_PERSISTENT_DESERIALIZATION_FILTER, null);
        // 规格非法时在此抛出（收紧项写错必须显形，不能悄悄退化成「不过滤」）
        ObjectInputFilter deserializationFilter = FileHttpSessionStorage.parseDeserializationFilter(filterSpec);
        java.nio.file.Path dir = java.nio.file.Paths.get(storeDir);
        if (deserializationFilter == null) {
            // 默认保持「不过滤」以免打断现有部署（功能优先），但必须让风险显形：
            // 能往 store-dir 写文件的人，可在下次启动时让任意 classpath 上的类被反序列化。
            // 配到 /tmp 之类全局可写目录时，这一点尤其要紧。
            log.warn("Session persistence is enabled WITHOUT a deserialization filter ({} is unset), store-dir={}, "
                    + "exclude={}: every class on the classpath may be deserialized from that directory, so anyone "
                    + "able to write it could execute code on the next restart. To lock it down, allow only what you "
                    + "actually store, e.g. "
                    + "'java.lang.*;java.util.*;java.math.*;java.time.*;maxarray=1000;maxdepth=20;maxrefs=10000;!*' "
                    + "(append your own packages; or '*' to keep allowing everything).",
                    PropertiesConstant.SERVLET_SESSION_PERSISTENT_DESERIALIZATION_FILTER, dir.toAbsolutePath(),
                    exclude);
        } else {
            log.info("Session persistence enabled, store-dir={}, exclude={}, deserialization-filter={}",
                    dir.toAbsolutePath(), exclude, filterSpec.trim());
        }
        return new FileHttpSessionStorage(dir, exclude, deserializationFilter);
    }

    /**
     * 创建单线程保存执行器（daemon，队列有界）。
     * <p>
     * 单线程是刻意的：同一会话的多次保存必须按触发顺序落盘（后写覆盖先写），多线程会让 ATOMIC_MOVE 之间的先后不可预测。 队列有界 + {@link ThreadPoolExecutor.AbortPolicy}，由
     * {@link #saveSessionAfterResponse} 捕获拒绝并丢弃，避免过载时反压 EventLoop。
     * </p>
     */
    private static ThreadPoolExecutor createSaveExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(SAVE_QUEUE_CAPACITY),
                r -> {
                    Thread thread = new Thread(r, "session-save");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
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
        // 只读视图；字段为 null 时保持原有返回 null 的行为
        return sessionListeners == null ? null : java.util.Collections.unmodifiableList(sessionListeners);
    }

    public List<HttpSessionAttributeListener> getAttributeListeners() {
        // 只读视图（与 getSessionListeners 同型）；字段为 null 时保持原有返回 null 的行为
        return attributeListeners == null ? null : java.util.Collections.unmodifiableList(attributeListeners);
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

    /**
     * 响应写完成后的会话保存入口（原 SessionFlushListener 直接调 {@link #saveSession}）。
     * <p>
     * 该入口运行在 Netty EventLoop 的写完成回调上，因此对阻塞型存储（文件/远程）必须切到后台线程： 在 EventLoop 上写盘会串行化该 loop 负责的所有连接。内存存储仍同步执行（一次
     * Map put，没必要多一次调度）。
     * </p>
     * <p>
     * 队列满时丢弃本次保存并告警：会话数据仍在内存中（后续请求会再次触发保存）， 宁可丢一次 lastAccessedTime/落盘，也不能反向阻塞请求线程。
     * </p>
     */
    public void saveSessionAfterResponse(PerfHttpSession session) {
        if (session == null || session.isInvalid()) {
            return;
        }
        if (!storage.hasBlockingSave() || saveExecutor == null) {
            session.markAccessed();
            saveSession(session);
            return;
        }
        try {
            saveExecutor.execute(() -> {
                try {
                    session.markAccessed();
                    saveSession(session);
                } catch (RuntimeException e) {
                    log.warn("Failed to persist session {}: {}", session.getId(), e.toString());
                }
            });
        } catch (RejectedExecutionException e) {
            if (saveQueueFullWarned.compareAndSet(false, true)) {
                log.warn("Session save queue is full (capacity {}), dropping this save. Session data stays in memory; "
                        + "further requests will retry. This indicates saves are slower than request rate.",
                        SAVE_QUEUE_CAPACITY);
            }
        }
    }

    @Override
    public void destroyComponent() throws Exception {
        shutdownSaveExecutor();
        storage.shutdown();
        super.destroyComponent();
    }

    /** 关闭保存线程并等待在途保存落盘（有界等待，避免停机被慢磁盘拖死）。 */
    private void shutdownSaveExecutor() {
        ThreadPoolExecutor executor = saveExecutor;
        if (executor == null) {
            return;
        }
        saveExecutor = null;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SAVE_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Session save queue did not drain within {}s, {} pending save(s) dropped",
                        SAVE_SHUTDOWN_TIMEOUT_SECONDS, executor.shutdownNow().size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    public PerfHttpSession changeSessionId(PerfHttpSession oldSession) {
        HttpSessionData oldData = oldSession.getData();
        String oldId = oldData.getId();
        // 属性复制必须与「旧会话摘除」在同一个临界区内：attributes 是 ConcurrentHashMap，
        // 其迭代器弱一致——若边复制边有并发写，会漏掉部分属性（登录态/token 静默丢失）。
        // 先在旧 id 的条带锁内摘除旧会话，此后并发 saveSession 会因 !containsKey(oldId)
        // 而被拒（见 FileHttpSessionStorage.saveSession 的守卫），复制期间不再有新写入。
        HttpSessionData newData = storage.replaceSessionCopyingAttributes(oldId, oldData);
        PerfHttpSession newSession = new PerfHttpSession(newData, servletContext, sessionListeners, attributeListeners);
        newSession.setNotNew();
        newSession.setOnInvalidateCallback(() -> storage.removeSession(newData.getId()));
        return newSession;
    }
}

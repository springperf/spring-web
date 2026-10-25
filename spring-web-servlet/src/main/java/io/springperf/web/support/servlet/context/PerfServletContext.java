package io.springperf.web.support.servlet.context;

import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.LifecycleWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.support.servlet.PerfRequestDispatcher;
import io.springperf.web.support.servlet.session.PerfHttpSessionManager;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import jakarta.servlet.descriptor.JspConfigDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class PerfServletContext implements ServletContext, LifecycleWebComponent {

    private static final Logger log = LoggerFactory.getLogger(PerfServletContext.class);

    private final WebContext webContext;
    private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();
    private final Map<String, String> initParameters = new ConcurrentHashMap<>();
    private final Map<String, String> mimeTypes;
    private final PerfSessionCookieConfig sessionCookieConfig = new PerfSessionCookieConfig();
    /** 本实例实际创建的临时目录（仅此目录可安全删除；未创建成功时为 null）。 */
    private final java.io.File tempDir;
    /** 暴露给 {@link ServletContext#TEMPDIR} 的属性值（创建失败回退到系统临时目录，保持非空）。 */
    private final java.io.File tempDirAttribute;

    /*
     * 会话/编码配置：启动期由配置写入，运行期仍可被 ServletContext 的 setter 修改， 读取方是请求线程（会话创建、请求/响应编码），故全部 volatile。
     */
    private volatile int sessionTimeout;
    private volatile String requestCharacterEncoding = "UTF-8";
    private volatile String responseCharacterEncoding = "UTF-8";
    /** server.servlet.encoding.force-request：为 true 时忽略业务对请求编码的显式设置。 */
    private volatile boolean forceRequestEncoding;
    /** server.servlet.encoding.force-response：为 true 时忽略业务对响应编码的显式设置。 */
    private volatile boolean forceResponseEncoding;
    /** 启动期从配置读取，运行期可被 {@link #setSessionTrackingModes} 修改；请求线程读取，故 volatile。 */
    private volatile Set<SessionTrackingMode> sessionTrackingModes = Collections.singleton(SessionTrackingMode.COOKIE);

    /** {@link ServletContextListener}：启动期 bean 扫描 + 运行期 {@code addListener} 追加。 */
    private final java.util.concurrent.CopyOnWriteArrayList<ServletContextListener> contextListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Servlet 注册表：Spring bean servlet 与 {@code addServlet} 动态注册共用同一张表， Phase1 由 {@code SupportServletRegistry}
     * 统一认领（init + 注册路由）。
     */
    private final java.util.concurrent.ConcurrentMap<String, DynamicServletRegistration> servletRegistrations = new java.util.concurrent.ConcurrentHashMap<>();

    /** Filter 注册表：Spring bean filter 由 {@code SupportWebFilterRegistry} 自行收集，这里只放 {@code addFilter} 的动态项。 */
    private final java.util.concurrent.ConcurrentHashMap<String, DynamicFilterRegistration> filterRegistrations = new java.util.concurrent.ConcurrentHashMap<>();

    /** url-pattern → servlet name：同一 pattern 不允许被两个 servlet 占用（规范要求由 addMapping 返回冲突集合）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, String> urlPatternOwners = new java.util.concurrent.ConcurrentHashMap<>();

    /** 动态注册窗口：Phase2 起（Phase1 的 contextInitialized 已全部回调完）按规范拒绝 addServlet/addFilter。 */
    private volatile boolean initialized;

    public PerfServletContext(WebContext webContext) {
        this.webContext = webContext;
        this.mimeTypes = loadMimeTypes();
        java.io.File base = new java.io.File(System.getProperty("java.io.tmpdir"));
        java.io.File dir = new java.io.File(base, "spring-perf-web-" + System.nanoTime());
        if (dir.mkdirs()) {
            this.tempDir = dir;
            this.tempDirAttribute = dir;
        } else {
            // 创建失败回退到系统临时目录，保证 TEMPDIR 属性非空（Jasper scratchdir 需要）
            this.tempDir = null;
            this.tempDirAttribute = base;
        }
        this.attributes.put(ServletContext.TEMPDIR, tempDirAttribute);
        readConfig();
    }

    /**
     * 销毁时清理应用级临时目录，避免多次启动在系统临时目录中累积泄漏。
     * <p>
     * 仅删除本实例实际创建的目录（{@code tempDir != null}）；创建失败回退到系统 临时目录时（{@code tempDir == null}）不执行删除，避免误删共享目录。
     * </p>
     */
    /**
     * 启动期收集 {@link ServletContextListener} bean 并派发 {@code contextInitialized}（原先是完全不派发）。
     * <p>
     * 运行期通过 {@code addListener} 注册的监听器不补发该事件 —— 事件已经发生，这与规范「只派发注册之后的事件」一致。
     * </p>
     */
    @Override
    public void initComponentPhase1() {
        org.springframework.context.ApplicationContext ctx = webContext.getCtx();
        if (ctx != null) {
            contextListeners.addAll(ctx.getBeansOfType(ServletContextListener.class).values());
        }
        if (contextListeners.isEmpty()) {
            return;
        }
        ServletContextEvent event = new ServletContextEvent(this);
        for (ServletContextListener listener : contextListeners) {
            try {
                listener.contextInitialized(event);
            } catch (RuntimeException e) {
                // 单个监听器失败不阻断启动（与容器行为一致），但必须带类名报出来
                log.error("ServletContextListener {} failed in contextInitialized", listener.getClass().getName(), e);
            }
        }
        log.info("ServletContext initialized with {} listener(s)", contextListeners.size());
    }

    /**
     * 关闭动态注册窗口。
     * <p>
     * 规范（Servlet 6.0 §4.4.1）只允许在 {@code ServletContextListener#contextInitialized} 或
     * {@code ServletContainerInitializer#onStartup} 期间调用 {@code addServlet}/{@code addFilter}；Phase1 结束即视为容器已初始化，
     * 之后再调用必须抛 {@link IllegalStateException}。
     * </p>
     */
    @Override
    public void initComponentPhase2() {
        this.initialized = true;
        log.debug(
                "Dynamic servlet/filter registration window closed: {} servlet registration(s), {} filter registration(s)",
                servletRegistrations.size(), filterRegistrations.size());
    }

    /**
     * 必须先于 {@code SupportServletRegistry} / {@code SupportWebFilterRegistry} 的 Phase1 执行： 只有先派发
     * {@code contextInitialized}，监听器里 {@code addServlet} 登记的项才能被它们认领。
     * <p>
     * 不能依赖声明顺序 —— 各组件的默认 order 相同，而排序输入来自 {@code ConcurrentHashMap.values()}，顺序不确定。
     * </p>
     */
    @Override
    public int getOrder() {
        return org.springframework.core.Ordered.LOWEST_PRECEDENCE - 20000;
    }

    @Override
    public void destroyComponent() {
        // Servlet 规范：contextDestroyed 先于其它组件销毁，且按初始化的逆序通知
        java.util.List<ServletContextListener> listeners = new java.util.ArrayList<>(contextListeners);
        for (int i = listeners.size() - 1; i >= 0; i--) {
            ServletContextListener listener = listeners.get(i);
            try {
                listener.contextDestroyed(new ServletContextEvent(this));
            } catch (RuntimeException e) {
                // 一个监听器失败不得中断停机流程，但必须带上下文报出来
                log.error("ServletContextListener {} failed in contextDestroyed", listener.getClass().getName(), e);
            }
        }
        contextListeners.clear();
        if (tempDir != null && tempDir.exists()) {
            deleteRecursively(tempDir);
            log.debug("Cleaned up temp dir {}", tempDir.getAbsolutePath());
        }
    }

    private static void deleteRecursively(java.io.File dir) {
        java.io.File[] children = dir.listFiles();
        if (children != null) {
            for (java.io.File child : children) {
                if (child.isDirectory()) {
                    deleteRecursively(child);
                }
                // 删除失败不静默：登记到 JVM 退出时再试（临时目录清理的兜底）
                if (!child.delete()) {
                    child.deleteOnExit();
                }
            }
        }
        if (!dir.delete()) {
            dir.deleteOnExit();
        }
    }

    private void readConfig() {
        // Spring Boot Duration 语义：裸数字按秒，支持 30m/1h/1d 后缀；默认 1800s = 30 分钟。
        // ServletContext.getSessionTimeout() 按规范返回分钟，由秒换算存储。
        this.sessionTimeout = readSessionTimeoutMinutes();
        // server.servlet.context-parameters.*：显式块预解析为 init parameters（去掉前缀）
        readExplicitContextParameters();
        // server.servlet.encoding.*：charset + force/force-request/force-response（启动期预解析）
        EncodingConfig encodingConfig = EncodingConfig.fromProperties(webContext.getProps());
        this.requestCharacterEncoding = encodingConfig.getCharset();
        this.responseCharacterEncoding = encodingConfig.getCharset();
        this.forceRequestEncoding = encodingConfig.isForceRequest();
        this.forceResponseEncoding = encodingConfig.isForceResponse();
        // session tracking-modes：COOKIE 与 URL 均真实生效；SSL 因 Netty 无容器 SSL session 概念而 N/A。
        String modes = webContext.getProps().get(PropertiesConstant.SERVLET_SESSION_TRACKING_MODES, null);
        if (modes != null && !modes.trim().isEmpty()) {
            java.util.EnumSet<SessionTrackingMode> parsed = java.util.EnumSet.noneOf(SessionTrackingMode.class);
            for (String s : modes.split(",")) {
                String t = s.trim().toUpperCase(java.util.Locale.ROOT);
                if (t.isEmpty()) {
                    continue;
                }
                try {
                    SessionTrackingMode m = SessionTrackingMode.valueOf(t);
                    if (m == SessionTrackingMode.SSL) {
                        // Netty 无容器 SSL session 概念，SSL 跟踪 N/A
                        log.warn("Session tracking mode SSL is not supported by this framework, ignored");
                    } else {
                        // COOKIE 走 Set-Cookie；URL 走 encodeURL 重写（见 PerfHttpServletResponse.encodeSessionUrl）
                        parsed.add(m);
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown session tracking mode: {}", t);
                }
            }
            if (!parsed.isEmpty()) {
                this.sessionTrackingModes = parsed;
            }
        }
    }

    private int readSessionTimeoutMinutes() {
        return readSessionTimeoutSeconds() / 60;
    }

    /**
     * 预解析 {@code server.servlet.context-parameters.*} 显式块：前缀后的键名作为 init parameter 名 （如
     * {@code server.servlet.context-parameters.foo=bar} → {@code foo=bar}）。 显式 {@code setInitParameter}
     * 调用优先（同名时不被配置块覆盖）。
     */
    private void readExplicitContextParameters() {
        String prefix = PropertiesConstant.SERVLET_CONTEXT_PARAMETERS_PREFIX;
        java.util.List<String> keys = webContext.getProps().getPropertyNames(prefix);
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            String name = key.substring(prefix.length());
            if (name.isEmpty()) {
                continue;
            }
            String value = webContext.getProps().get(key, null);
            if (value != null) {
                initParameters.putIfAbsent(name, value);
            }
        }
    }

    private int readSessionTimeoutSeconds() {
        // 统一走 ApplicationProperties.getDurationSeconds（Boot Duration 语义：裸数字=秒，支持 30s/1m/1h/1d），
        // 无效值 warn 并回退默认 1800s。
        try {
            return (int) Math.max(0, webContext.getProps().getDurationSeconds("server.servlet.session.timeout", 1800));
        } catch (Exception ex) {
            log.warn("Invalid server.servlet.session.timeout, falling back to default 1800s", ex);
            return 1800;
        }
    }

    // ===================== Context Path =====================

    @Override
    public String getContextPath() {
        return webContext.getContextPath();
    }

    // ===================== Attributes =====================

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }

    @Override
    public void setAttribute(String name, Object object) {
        if (object == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, object);
        }
    }

    @Override
    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    // ===================== Init Parameters =====================

    @Override
    public String getInitParameter(String name) {
        String val = initParameters.get(name);
        if (val == null) {
            val = webContext.getProps().get(name, null);
        }
        return val;
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParameters.keySet());
    }

    @Override
    public boolean setInitParameter(String name, String value) {
        if (initParameters.containsKey(name)) {
            return false;
        }
        initParameters.put(name, value);
        return true;
    }

    // ===================== Server Info =====================

    @Override
    public String getServerInfo() {
        return "spring-perf-web";
    }

    @Override
    public String getVirtualServerName() {
        return webContext.getProps().get(PropertiesConstant.SERVLET_VIRTUAL_SERVER_NAME,
                PropertiesConstant.SERVLET_VIRTUAL_SERVER_NAME_DEFAULT);
    }

    @Override
    public int getMajorVersion() {
        return 6;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public int getEffectiveMajorVersion() {
        return 6;
    }

    @Override
    public int getEffectiveMinorVersion() {
        return 0;
    }

    @Override
    public String getServletContextName() {
        return webContext.getProps().get(PropertiesConstant.SERVLET_APPLICATION_DISPLAY_NAME, "spring-perf-web");
    }

    // ===================== MIME Types =====================

    @Override
    public String getMimeType(String file) {
        if (file == null) {
            return null;
        }
        int dot = file.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        String extension = file.substring(dot + 1).toLowerCase();
        return mimeTypes.get(extension);
    }

    // ===================== Resources =====================

    @Override
    public String getRealPath(String path) {
        if (path == null) {
            return null;
        }
        URL resource = getResource(path);
        if (resource != null && "file".equals(resource.getProtocol())) {
            return resource.getPath();
        }
        return null;
    }

    @Override
    public URL getResource(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = getClass().getClassLoader();
        }
        URL url = cl.getResource("META-INF/resources/" + normalized);
        if (url != null) {
            return url;
        }
        url = cl.getResource("static/" + normalized);
        if (url != null) {
            return url;
        }
        url = cl.getResource("public/" + normalized);
        if (url != null) {
            return url;
        }
        url = cl.getResource(normalized);
        if (url != null) {
            return url;
        }
        return null;
    }

    @Override
    public InputStream getResourceAsStream(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = getClass().getClassLoader();
        }
        InputStream in = cl.getResourceAsStream("META-INF/resources/" + normalized);
        if (in != null) {
            return in;
        }
        in = cl.getResourceAsStream("static/" + normalized);
        if (in != null) {
            return in;
        }
        in = cl.getResourceAsStream("public/" + normalized);
        if (in != null) {
            return in;
        }
        in = cl.getResourceAsStream(normalized);
        if (in != null) {
            return in;
        }
        return null;
    }

    @Override
    public Set<String> getResourcePaths(String path) {
        return Collections.emptySet();
    }

    // ===================== RequestDispatcher =====================

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        if (path == null) {
            return null;
        }
        return new PerfRequestDispatcher(path);
    }

    @Override
    public RequestDispatcher getNamedDispatcher(String name) {
        if (name == null) {
            return null;
        }
        return new PerfRequestDispatcher("/" + name);
    }

    // ===================== Context =====================

    @Override
    public ServletContext getContext(String uripath) {
        return null;
    }

    // ===================== Logging =====================

    @Override
    public void log(String msg) {
        log.info(msg);
    }

    @Override
    public void log(String message, Throwable throwable) {
        log.error(message, throwable);
    }

    // ===================== Servlet Registration =====================

    /**
     * 窗口外注册的统一失败方式：规范要求容器初始化完成后 {@code addServlet}/{@code addFilter} 抛 {@link IllegalStateException}，而不是"注册成功但永不生效"。
     */
    private void requireRegistrationOpen(String method) {
        if (initialized) {
            throw new IllegalStateException(method + " must be called during ServletContext initialization "
                    + "(ServletContextListener#contextInitialized). Dynamic registration after startup is not "
                    + "allowed by the Servlet specification; declare a Spring bean or @WebServlet/@WebFilter instead.");
        }
    }

    private static String validateRegistrationName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Registration name must not be null or empty");
        }
        return name;
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, String className) {
        requireRegistrationOpen("addServlet(String, String)");
        String name = validateRegistrationName(servletName);
        if (className == null) {
            throw new IllegalArgumentException("Servlet class name must not be null");
        }
        Class<?> clazz;
        try {
            clazz = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Servlet class not found: " + className, e);
        }
        if (!jakarta.servlet.Servlet.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException("Class does not implement jakarta.servlet.Servlet: " + className);
        }
        @SuppressWarnings("unchecked")
        Class<? extends jakarta.servlet.Servlet> servletClass = (Class<? extends jakarta.servlet.Servlet>) clazz;
        return addServlet(name, servletClass);
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, jakarta.servlet.Servlet servlet) {
        requireRegistrationOpen("addServlet(String, Servlet)");
        String name = validateRegistrationName(servletName);
        if (servlet == null) {
            throw new IllegalArgumentException("Servlet must not be null");
        }
        DynamicServletRegistration registration = new DynamicServletRegistration(name, servlet, this);
        if (servletRegistrations.putIfAbsent(name, registration) != null) {
            throw new IllegalArgumentException(
                    "A servlet registration with name '" + name + "' already exists in this ServletContext");
        }
        log.debug("Dynamically registered servlet '{}' ({})", name, servlet.getClass().getName());
        return registration;
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName,
            Class<? extends jakarta.servlet.Servlet> servletClass) {
        requireRegistrationOpen("addServlet(String, Class)");
        String name = validateRegistrationName(servletName);
        if (servletClass == null) {
            throw new IllegalArgumentException("Servlet class must not be null");
        }
        return addServlet(name, instantiate(servletClass, "servlet"));
    }

    @Override
    public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) {
        // JSP 已有统一路由（JspViewResolver#/**/*.jsp），再开一个 servlet 级 jspFile 映射会与它竞争同一条路径
        throw new UnsupportedOperationException("addJspFile is not supported: JSPs are routed by JspViewResolver "
                + "(/**/*.jsp). Return a 'jsp:name' view, or addServlet(JasperJspServlet.class) with an explicit mapping.");
    }

    @Override
    public <T extends jakarta.servlet.Servlet> T createServlet(Class<T> c) {
        return instantiate(c, "servlet");
    }

    @Override
    public ServletRegistration getServletRegistration(String servletName) {
        return servletName == null ? null : servletRegistrations.get(servletName);
    }

    @Override
    public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        return Collections.unmodifiableMap(new java.util.LinkedHashMap<>(servletRegistrations));
    }

    /**
     * 登记一个 Spring bean servlet（Phase1 由 {@code SupportServletRegistry} 调用）。
     * <p>
     * 与动态注册共用同一张注册表，{@link #getServletRegistrations()} 才能返回完整视图。同名冲突属于配置错误（fail-fast）： 两个来源都想用同一个 servlet
     * name，运行期无法判断该用哪个。
     * </p>
     *
     * @param name
     *            注册名
     * @param servlet
     *            servlet 实例（bean）
     * @param urlPatterns
     *            已解析的 url-pattern
     * @param initParams
     *            已解析的 init parameters
     *
     * @return 注册项
     */
    public ServletRegistration.Dynamic registerBeanServlet(String name, jakarta.servlet.Servlet servlet,
            String[] urlPatterns, Map<String, String> initParams) {
        String registrationName = validateRegistrationName(name);
        DynamicServletRegistration registration = new DynamicServletRegistration(registrationName, servlet, this);
        if (initParams != null && !initParams.isEmpty()) {
            registration.setInitParameters(initParams);
        }
        java.util.Set<String> conflicts = registration.addMapping(urlPatterns);
        if (!conflicts.isEmpty()) {
            throw new IllegalStateException("Servlet '" + registrationName
                    + "' maps url-pattern(s) already taken by another servlet: " + conflicts);
        }
        if (servletRegistrations.putIfAbsent(registrationName, registration) != null) {
            throw new IllegalStateException(
                    "Duplicate servlet registration name '" + registrationName + "': declared by "
                            + servlet.getClass().getName() + " and another registration. Rename one of them.");
        }
        return registration;
    }

    /**
     * 取走尚未认领的 servlet 注册项（Phase1 由 {@code SupportServletRegistry} 调用）。
     * <p>
     * 认领后条目仍保留在 {@link #getServletRegistrations()} 视图中，只是不再重复 init / 注册路由。
     * </p>
     */
    public java.util.List<DynamicServletRegistration> claimServletRegistrations() {
        java.util.List<DynamicServletRegistration> claimed = new java.util.ArrayList<>();
        for (DynamicServletRegistration registration : servletRegistrations.values()) {
            if (registration.markClaimedIfUnclaimed()) {
                claimed.add(registration);
            }
        }
        return claimed;
    }

    /** 同一 url-pattern 是否已被另一个 servlet 占用（动态注册的 addMapping 用它返回冲突集合）。 */
    boolean isUrlPatternTaken(String urlPattern, String servletName) {
        String owner = urlPatternOwners.putIfAbsent(urlPattern, servletName);
        return owner != null && !owner.equals(servletName);
    }

    // ===================== Filter Registration =====================

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, String className) {
        requireRegistrationOpen("addFilter(String, String)");
        String name = validateRegistrationName(filterName);
        if (className == null) {
            throw new IllegalArgumentException("Filter class name must not be null");
        }
        Class<?> clazz;
        try {
            clazz = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Filter class not found: " + className, e);
        }
        if (!jakarta.servlet.Filter.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException("Class does not implement jakarta.servlet.Filter: " + className);
        }
        @SuppressWarnings("unchecked")
        Class<? extends jakarta.servlet.Filter> filterClass = (Class<? extends jakarta.servlet.Filter>) clazz;
        return addFilter(name, filterClass);
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, jakarta.servlet.Filter filter) {
        requireRegistrationOpen("addFilter(String, Filter)");
        String name = validateRegistrationName(filterName);
        if (filter == null) {
            throw new IllegalArgumentException("Filter must not be null");
        }
        DynamicFilterRegistration registration = new DynamicFilterRegistration(name, filter);
        if (filterRegistrations.putIfAbsent(name, registration) != null) {
            throw new IllegalArgumentException(
                    "A filter registration with name '" + name + "' already exists in this ServletContext");
        }
        log.debug("Dynamically registered filter '{}' ({})", name, filter.getClass().getName());
        return registration;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName,
            Class<? extends jakarta.servlet.Filter> filterClass) {
        requireRegistrationOpen("addFilter(String, Class)");
        String name = validateRegistrationName(filterName);
        if (filterClass == null) {
            throw new IllegalArgumentException("Filter class must not be null");
        }
        return addFilter(name, instantiate(filterClass, "filter"));
    }

    @Override
    public <T extends jakarta.servlet.Filter> T createFilter(Class<T> c) {
        return instantiate(c, "filter");
    }

    @Override
    public FilterRegistration getFilterRegistration(String filterName) {
        return filterName == null ? null : filterRegistrations.get(filterName);
    }

    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        return Collections.unmodifiableMap(new java.util.LinkedHashMap<>(filterRegistrations));
    }

    /**
     * 取走尚未认领的 filter 注册项（Phase3 由 {@code SupportWebFilterRegistry} 调用）。
     * <p>
     * 与 servlet 不同，Spring bean 的 filter 由 {@code SupportWebFilterRegistry} 自己的容器收集， 因此
     * {@link #getFilterRegistrations()} 只反映动态注册项。
     * </p>
     */
    public java.util.List<DynamicFilterRegistration> claimFilterRegistrations() {
        java.util.List<DynamicFilterRegistration> claimed = new java.util.ArrayList<>();
        for (DynamicFilterRegistration registration : filterRegistrations.values()) {
            if (registration.markClaimedIfUnclaimed()) {
                claimed.add(registration);
            }
        }
        return claimed;
    }

    // ===================== Listeners =====================

    /**
     * 动态注册监听器（原先是空实现：注册「成功」但永不回调，是最难排查的一类静默）。
     * <p>
     * 支持 {@link ServletContextListener}（生命周期）与 {@link HttpSessionListener} / {@link HttpSessionAttributeListener} /
     * {@link HttpSessionIdListener}（会话）。不支持的类型抛 {@link IllegalArgumentException} ——
     * 请求级监听器（{@code ServletRequestListener} 等）本框架没有请求事件派发点， 注册即等于画饼，必须显形。
     * </p>
     * <p>
     * 与 Tomcat 的差异（有意）：Tomcat 在容器初始化后调用 {@code addListener} 会抛 {@link IllegalStateException}；本框架允许运行期注册， 但只派发注册之后发生的事件
     * —— 需要完整生命周期回调（尤其是 {@code contextInitialized}）的监听器请声明为 Spring bean。
     * </p>
     */
    @Override
    public void addListener(String className) {
        if (className == null) {
            throw new IllegalArgumentException("Listener class name must not be null");
        }
        Class<?> clazz;
        try {
            clazz = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Listener class not found: " + className, e);
        }
        if (!EventListener.class.isAssignableFrom(clazz)) {
            throw new IllegalArgumentException("Class does not implement java.util.EventListener: " + className);
        }
        @SuppressWarnings("unchecked")
        Class<? extends EventListener> listenerClass = (Class<? extends EventListener>) clazz;
        addListener(listenerClass);
    }

    @Override
    public <T extends EventListener> T createListener(Class<T> c) {
        return instantiateListener(c);
    }

    @Override
    public void addListener(Class<? extends EventListener> listenerClass) {
        if (listenerClass == null) {
            throw new IllegalArgumentException("Listener class must not be null");
        }
        addListener(instantiateListener(listenerClass));
    }

    @Override
    public <T extends EventListener> void addListener(T t) {
        if (t == null) {
            throw new IllegalArgumentException("Listener must not be null");
        }
        if (t instanceof ServletContextListener) {
            contextListeners.add((ServletContextListener) t);
            log.debug("Registered ServletContextListener {}", t.getClass().getName());
            return;
        }
        if (t instanceof HttpSessionListener) {
            requireSessionManager(t).addSessionListener((HttpSessionListener) t);
            return;
        }
        if (t instanceof HttpSessionAttributeListener) {
            requireSessionManager(t).addAttributeListener((HttpSessionAttributeListener) t);
            return;
        }
        if (t instanceof HttpSessionIdListener) {
            requireSessionManager(t).addSessionIdListener((HttpSessionIdListener) t);
            return;
        }
        throw new IllegalArgumentException("Unsupported listener type: " + t.getClass().getName()
                + ". Supported: ServletContextListener, HttpSessionListener, HttpSessionAttributeListener, "
                + "HttpSessionIdListener. (HttpSessionBindingListener is registered as a session attribute; "
                + "request-level listeners have no dispatch point in this framework.)");
    }

    /**
     * 实例化监听器：反射创建后交给 Spring 做一次依赖注入（与 bean 语义对齐，但不注册为 bean）。
     *
     * @throws IllegalArgumentException
     *             无公共无参构造器 / 构造失败 / 类型非法
     */
    private <T extends EventListener> T instantiateListener(Class<T> listenerClass) {
        if (listenerClass == null) {
            throw new IllegalArgumentException("Listener class must not be null");
        }
        return instantiate(listenerClass, "listener");
    }

    /**
     * 反射实例化 + 交给 Spring 做一次依赖注入（与 bean 语义对齐，但不注册为 bean）。
     * <p>
     * {@code createServlet}/{@code createFilter}/监听器实例化共用：动态注册的组件同样能拿到 Spring 依赖。
     * </p>
     */
    private <T> T instantiate(Class<T> type, String kind) {
        if (type == null) {
            throw new IllegalArgumentException(kind + " class must not be null");
        }
        T instance;
        try {
            instance = type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | SecurityException e) {
            throw new IllegalArgumentException(
                    "Cannot instantiate " + kind + " " + type.getName() + " (a public no-arg constructor is required)",
                    e);
        }
        org.springframework.context.ApplicationContext ctx = webContext.getCtx();
        if (ctx != null) {
            try {
                ctx.getAutowireCapableBeanFactory().autowireBean(instance);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Failed to autowire " + kind + " " + type.getName(), e);
            }
        }
        return instance;
    }

    private PerfHttpSessionManager requireSessionManager(EventListener listener) {
        PerfHttpSessionManager manager = webContext.getWebComponent(PerfHttpSessionManager.class);
        if (manager == null) {
            throw new IllegalStateException("Cannot register " + listener.getClass().getName()
                    + ": session support is not enabled (PerfHttpSessionManager is not registered)");
        }
        return manager;
    }

    // ===================== Session =====================

    @Override
    public SessionCookieConfig getSessionCookieConfig() {
        return sessionCookieConfig;
    }

    /**
     * 设置有效的会话跟踪模式（原先为空实现：程序化配置被静默丢弃，与 {@code getEffectiveSessionTrackingModes()} 的返回值自相矛盾）。
     * <p>
     * 规范约束：集合不得为空，且必须包含 {@link SessionTrackingMode#COOKIE} 或 {@link SessionTrackingMode#SSL}（URL 重写只能作为补充手段）。 生效后会话
     * Cookie 的下发与 {@code encodeURL} 的 URL 重写都会按新配置工作。
     * </p>
     */
    @Override
    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) {
        if (sessionTrackingModes == null || sessionTrackingModes.isEmpty()) {
            throw new IllegalArgumentException("sessionTrackingModes must not be null or empty");
        }
        if (!sessionTrackingModes.contains(SessionTrackingMode.COOKIE)
                && !sessionTrackingModes.contains(SessionTrackingMode.SSL)) {
            throw new IllegalArgumentException(
                    "COOKIE or SSL must be present in sessionTrackingModes: " + sessionTrackingModes);
        }
        this.sessionTrackingModes = Collections.unmodifiableSet(EnumSet.copyOf(sessionTrackingModes));
    }

    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return sessionTrackingModes;
    }

    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return sessionTrackingModes;
    }

    @Override
    public int getSessionTimeout() {
        return sessionTimeout;
    }

    @Override
    public void setSessionTimeout(int sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
    }

    // ===================== Character Encoding =====================

    @Override
    public String getRequestCharacterEncoding() {
        return requestCharacterEncoding;
    }

    @Override
    public void setRequestCharacterEncoding(String encoding) {
        // force-request=true：忽略业务显式设置，保持配置 charset
        if (forceRequestEncoding) {
            return;
        }
        this.requestCharacterEncoding = encoding;
    }

    @Override
    public String getResponseCharacterEncoding() {
        return responseCharacterEncoding;
    }

    @Override
    public void setResponseCharacterEncoding(String encoding) {
        // force-response=true：忽略业务显式设置，保持配置 charset
        if (forceResponseEncoding) {
            return;
        }
        this.responseCharacterEncoding = encoding;
    }

    /** server.servlet.encoding.force-request：是否强制请求编码。 */
    public boolean isForceRequestEncoding() {
        return forceRequestEncoding;
    }

    /** server.servlet.encoding.force-response：是否强制响应编码。 */
    public boolean isForceResponseEncoding() {
        return forceResponseEncoding;
    }

    // ===================== Others =====================

    @Override
    public ClassLoader getClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    @Override
    public void declareRoles(String... roleNames) {
    }

    @Override
    public JspConfigDescriptor getJspConfigDescriptor() {
        return null;
    }

    // ===================== MIME Type Map =====================

    private static Map<String, String> loadMimeTypes() {
        Map<String, String> map = new HashMap<>();
        map.put("html", "text/html");
        map.put("htm", "text/html");
        map.put("xhtml", "application/xhtml+xml");
        map.put("css", "text/css");
        map.put("js", "application/javascript");
        map.put("mjs", "application/javascript");
        map.put("json", "application/json");
        map.put("xml", "application/xml");
        map.put("xsl", "application/xml");
        map.put("xslt", "application/xslt+xml");
        map.put("rss", "application/rss+xml");
        map.put("atom", "application/atom+xml");
        map.put("yaml", "application/x-yaml");
        map.put("yml", "application/x-yaml");
        map.put("txt", "text/plain");
        map.put("text", "text/plain");
        map.put("csv", "text/csv");
        map.put("tsv", "text/tab-separated-values");
        map.put("png", "image/png");
        map.put("jpg", "image/jpeg");
        map.put("jpeg", "image/jpeg");
        map.put("gif", "image/gif");
        map.put("svg", "image/svg+xml");
        map.put("svgz", "image/svg+xml");
        map.put("ico", "image/x-icon");
        map.put("webp", "image/webp");
        map.put("bmp", "image/bmp");
        map.put("tiff", "image/tiff");
        map.put("tif", "image/tiff");
        map.put("avif", "image/avif");
        map.put("pdf", "application/pdf");
        map.put("zip", "application/zip");
        map.put("tar", "application/x-tar");
        map.put("gz", "application/gzip");
        map.put("gzip", "application/gzip");
        map.put("bz2", "application/x-bzip2");
        map.put("7z", "application/x-7z-compressed");
        map.put("rar", "application/vnd.rar");
        map.put("mp4", "video/mp4");
        map.put("m4v", "video/mp4");
        map.put("mp3", "audio/mpeg");
        map.put("mpeg", "video/mpeg");
        map.put("mpg", "video/mpeg");
        map.put("ogg", "audio/ogg");
        map.put("ogv", "video/ogg");
        map.put("webm", "video/webm");
        map.put("wav", "audio/wav");
        map.put("flac", "audio/flac");
        map.put("aac", "audio/aac");
        map.put("woff", "font/woff");
        map.put("woff2", "font/woff2");
        map.put("ttf", "font/ttf");
        map.put("otf", "font/otf");
        map.put("eot", "application/vnd.ms-fontobject");
        map.put("wasm", "application/wasm");
        map.put("map", "application/json");
        return Collections.unmodifiableMap(map);
    }

    // ===================== SessionCookieConfig =====================

    /** 不引用外部实例，故为 static：避免每个实例持有隐式外部引用（SIC_INNER_SHOULD_BE_STATIC）。 */
    private static class PerfSessionCookieConfig implements SessionCookieConfig {

        private String name;
        private String domain;
        private String path;
        private String comment;
        private boolean httpOnly = true;
        private boolean secure;
        private int maxAge = -1;
        private final Map<String, String> attributes = new HashMap<>();

        @Override
        public void setName(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public void setDomain(String domain) {
            this.domain = domain;
        }

        @Override
        public String getDomain() {
            return domain;
        }

        @Override
        public void setPath(String path) {
            this.path = path;
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public void setComment(String comment) {
            this.comment = comment;
        }

        @Override
        public String getComment() {
            return comment;
        }

        @Override
        public void setHttpOnly(boolean httpOnly) {
            this.httpOnly = httpOnly;
        }

        @Override
        public boolean isHttpOnly() {
            return httpOnly;
        }

        @Override
        public void setSecure(boolean secure) {
            this.secure = secure;
        }

        @Override
        public boolean isSecure() {
            return secure;
        }

        @Override
        public void setMaxAge(int maxAge) {
            this.maxAge = maxAge;
        }

        @Override
        public int getMaxAge() {
            return maxAge;
        }

        @Override
        public String getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public void setAttribute(String name, String value) {
            if (value == null) {
                attributes.remove(name);
            } else {
                attributes.put(name, value);
            }
        }

        @Override
        public Map<String, String> getAttributes() {
            return Collections.unmodifiableMap(attributes);
        }
    }
}

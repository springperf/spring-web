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
     * 动态注册不受支持时的统一失败方式。
     * <p>
     * 规范为这些方法指定的「不支持」信号就是 {@link UnsupportedOperationException}；原先返回 null 会让调用方 以为注册成功（或静默跳过），能力丢失却无任何痕迹 —— 与模块其余
     * fail-fast 策略也不一致。
     * </p>
     */
    private static UnsupportedOperationException dynamicRegistrationUnsupported(String method) {
        return new UnsupportedOperationException(method + " is not supported: this framework has no Servlet container "
                + "registry. Register a Spring bean instead (or annotate with @WebServlet/@WebFilter).");
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, String className) {
        throw dynamicRegistrationUnsupported("addServlet(String, String)");
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, jakarta.servlet.Servlet servlet) {
        throw dynamicRegistrationUnsupported("addServlet(String, Servlet)");
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName,
            Class<? extends jakarta.servlet.Servlet> servletClass) {
        throw dynamicRegistrationUnsupported("addServlet(String, Class)");
    }

    @Override
    public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) {
        throw dynamicRegistrationUnsupported("addJspFile(String, String)");
    }

    @Override
    public <T extends jakarta.servlet.Servlet> T createServlet(Class<T> c) {
        throw dynamicRegistrationUnsupported("createServlet(Class)");
    }

    @Override
    public ServletRegistration getServletRegistration(String servletName) {
        return null;
    }

    @Override
    public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        return Collections.emptyMap();
    }

    // ===================== Filter Registration =====================

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, String className) {
        throw dynamicRegistrationUnsupported("addFilter(String, String)");
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, jakarta.servlet.Filter filter) {
        throw dynamicRegistrationUnsupported("addFilter(String, Filter)");
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName,
            Class<? extends jakarta.servlet.Filter> filterClass) {
        throw dynamicRegistrationUnsupported("addFilter(String, Class)");
    }

    @Override
    public <T extends jakarta.servlet.Filter> T createFilter(Class<T> c) {
        throw dynamicRegistrationUnsupported("createFilter(Class)");
    }

    @Override
    public FilterRegistration getFilterRegistration(String filterName) {
        return null;
    }

    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        return Collections.emptyMap();
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
        T listener;
        try {
            listener = listenerClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | SecurityException e) {
            throw new IllegalArgumentException("Cannot instantiate listener " + listenerClass.getName()
                    + " (a public no-arg constructor is required)", e);
        }
        org.springframework.context.ApplicationContext ctx = webContext.getCtx();
        if (ctx != null) {
            try {
                ctx.getAutowireCapableBeanFactory().autowireBean(listener);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Failed to autowire listener " + listenerClass.getName(), e);
            }
        }
        return listener;
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

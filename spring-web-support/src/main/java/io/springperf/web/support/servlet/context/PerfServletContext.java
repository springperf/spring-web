package io.springperf.web.support.servlet.context;

import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.support.servlet.PerfRequestDispatcher;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.descriptor.JspConfigDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class PerfServletContext implements ServletContext, WebComponent {

    private static final Logger log = LoggerFactory.getLogger(PerfServletContext.class);

    private final WebContext webContext;
    private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();
    private final Map<String, String> initParameters = new ConcurrentHashMap<>();
    private final Map<String, String> mimeTypes;
    private final PerfSessionCookieConfig sessionCookieConfig = new PerfSessionCookieConfig();

    private int sessionTimeout;
    private String requestCharacterEncoding = "UTF-8";
    private String responseCharacterEncoding = "UTF-8";

    public PerfServletContext(WebContext webContext) {
        this.webContext = webContext;
        this.mimeTypes = loadMimeTypes();
        this.attributes.put(ServletContext.TEMPDIR, createTempDir());
        readConfig();
    }

    /**
     * 创建应用级临时目录，作为 {@link ServletContext#TEMPDIR} 属性。
     * 供 JSP 编译（Jasper scratchdir）等容器能力使用。
     */
    private static java.io.File createTempDir() {
        java.io.File base = new java.io.File(System.getProperty("java.io.tmpdir"));
        java.io.File dir = new java.io.File(base, "spring-perf-web-" + System.nanoTime());
        if (!dir.exists() && !dir.mkdirs()) {
            return base;
        }
        return dir;
    }

    private void readConfig() {
        int timeout = webContext.getProps().getInt("server.servlet.session.timeout");
        this.sessionTimeout = timeout > 0 ? timeout : 1800;
        String reqEnc = webContext.getProps().get("server.servlet.encoding.request", "UTF-8");
        if (reqEnc != null) {
            this.requestCharacterEncoding = reqEnc;
        }
        String respEnc = webContext.getProps().get("server.servlet.encoding.response", "UTF-8");
        if (respEnc != null) {
            this.responseCharacterEncoding = respEnc;
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
        return webContext.getProps().get("server.virtual-host", "localhost");
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
        return webContext.getProps().get("server.servlet.application-name", "spring-perf-web");
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

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, String className) {
        return null;
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, jakarta.servlet.Servlet servlet) {
        return null;
    }

    @Override
    public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends jakarta.servlet.Servlet> servletClass) {
        return null;
    }

    @Override
    public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) {
        return null;
    }

    @Override
    public <T extends jakarta.servlet.Servlet> T createServlet(Class<T> c) {
        return null;
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
        return null;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, jakarta.servlet.Filter filter) {
        return null;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends jakarta.servlet.Filter> filterClass) {
        return null;
    }

    @Override
    public <T extends jakarta.servlet.Filter> T createFilter(Class<T> c) {
        return null;
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

    @Override
    public void addListener(String className) {
    }

    @Override
    public <T extends EventListener> T createListener(Class<T> c) {
        return null;
    }

    @Override
    public void addListener(Class<? extends EventListener> listenerClass) {
    }

    @Override
    public <T extends EventListener> void addListener(T t) {
    }

    // ===================== Session =====================

    @Override
    public SessionCookieConfig getSessionCookieConfig() {
        return sessionCookieConfig;
    }

    @Override
    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) {
    }

    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return Collections.singleton(SessionTrackingMode.COOKIE);
    }

    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return Collections.singleton(SessionTrackingMode.COOKIE);
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
        this.requestCharacterEncoding = encoding;
    }

    @Override
    public String getResponseCharacterEncoding() {
        return responseCharacterEncoding;
    }

    @Override
    public void setResponseCharacterEncoding(String encoding) {
        this.responseCharacterEncoding = encoding;
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

    private class PerfSessionCookieConfig implements SessionCookieConfig {

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
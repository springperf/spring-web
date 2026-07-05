package io.springperf.web.support.servlet.session;

import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.RequestAttribute;
import org.springframework.core.Ordered;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class PerfHttpSessionManager extends BaseWebComponent {

    public static final RequestAttribute<PerfHttpSession> SESSION_ATTR_KEY =
            RequestAttribute.createAttribute(PerfHttpSession.class);

    // ---- Cookie 配置键 ----
    static final String COOKIE_NAME_KEY = "server.servlet.session.cookie.name";
    static final String COOKIE_SAME_SITE_KEY = "server.servlet.session.cookie.same-site";
    static final String COOKIE_SECURE_KEY = "server.servlet.session.cookie.secure";

    public static final String DEFAULT_SESSION_COOKIE_NAME = "JSESSIONID";
    static final String REQUESTED_SESSION_ID_ATTR = PerfHttpSessionManager.class.getName() + ".REQUESTED_SESSION_ID";

    private HttpSessionStorage storage;
    private ServletContext servletContext;
    private List<HttpSessionListener> sessionListeners = Collections.emptyList();
    private List<HttpSessionAttributeListener> attributeListeners = Collections.emptyList();

    // ---- Cookie 配置 ----
    private String cookieName = DEFAULT_SESSION_COOKIE_NAME;
    private String cookiePath = "/";
    private String sameSite;
    private boolean cookieSecure;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        this.servletContext = PerfHttpSession.createMinimalServletContext();
        HttpSessionStorage bean = webContext.getBeanFromCtx(HttpSessionStorage.class);
        this.storage = bean != null ? bean : new InMemoryHttpSessionStorage();
        // Scan for HttpSessionListener and HttpSessionAttributeListener beans
        this.sessionListeners = new ArrayList<>(
                webContext.getCtx().getBeansOfType(HttpSessionListener.class).values());
        this.attributeListeners = new ArrayList<>(
                webContext.getCtx().getBeansOfType(HttpSessionAttributeListener.class).values());

        // Read cookie configuration
        this.cookieName = webContext.getProps().get(COOKIE_NAME_KEY, DEFAULT_SESSION_COOKIE_NAME);
        this.cookieSecure = webContext.getProps().getBoolean(COOKIE_SECURE_KEY, false);
        String configuredSameSite = webContext.getProps().get(COOKIE_SAME_SITE_KEY, "");
        this.sameSite = configuredSameSite.isEmpty() ? null : configuredSameSite.toUpperCase();
        String ctxPath = webContext.getContextPath();
        this.cookiePath = (ctxPath == null || ctxPath.isEmpty() || "/".equals(ctxPath)) ? "/" : ctxPath;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10000;
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
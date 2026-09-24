package io.springperf.web.support.view;

import jakarta.servlet.http.HttpSession;
import org.thymeleaf.web.IWebSession;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.Enumeration;

/**
 * {@link IWebSession} 的 Servlet 适配：桥接 {@link HttpSession}。
 * <p>
 * 仅在 {@code HttpSession} 非 null 时实例化；{@link #exists()} 恒返回 {@code true} （包装实例的存在即表示会话存在）。
 * </p>
 *
 * @since 3.5.7
 */
public class ServletWebSession implements IWebSession {

    private final HttpSession session;

    public ServletWebSession(HttpSession session) {
        this.session = session;
    }

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public boolean containsAttribute(String name) {
        return session.getAttribute(name) != null;
    }

    @Override
    public int getAttributeCount() {
        return getAllAttributeNames().size();
    }

    @Override
    public Set<String> getAllAttributeNames() {
        Enumeration<String> names = session.getAttributeNames();
        if (names == null) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<>();
        while (names.hasMoreElements()) {
            result.add(names.nextElement());
        }
        return result;
    }

    @Override
    public Map<String, Object> getAttributeMap() {
        Set<String> names = getAllAttributeNames();
        Map<String, Object> result = new HashMap<>(names.size());
        for (String name : names) {
            result.put(name, session.getAttribute(name));
        }
        return result;
    }

    @Override
    public Object getAttributeValue(String name) {
        return session.getAttribute(name);
    }

    @Override
    public void setAttributeValue(String name, Object value) {
        session.setAttribute(name, value);
    }

    @Override
    public void removeAttribute(String name) {
        session.removeAttribute(name);
    }
}

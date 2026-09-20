package io.springperf.web.view.exchange;

import org.thymeleaf.web.IWebApplication;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 基于 classpath 的 {@link IWebApplication} 适配（零 Servlet 依赖）。
 *
 * <p>属性存于本实例的 map（应用级共享需由调用方持有同一实例）；
 * 资源读取走类加载器。</p>
 *
 * @since 3.5.7
 */
public class PerfWebApplication implements IWebApplication {

    private final Map<String, Object> attrs = new HashMap<>();

    @Override
    public boolean containsAttribute(String name) { return attrs.containsKey(name); }

    @Override
    public int getAttributeCount() { return attrs.size(); }

    @Override
    public Set<String> getAllAttributeNames() { return attrs.keySet(); }

    @Override
    public Map<String, Object> getAttributeMap() { return attrs; }

    @Override
    public Object getAttributeValue(String name) { return attrs.get(name); }

    @Override
    public void setAttributeValue(String name, Object value) { attrs.put(name, value); }

    @Override
    public void removeAttribute(String name) { attrs.remove(name); }

    @Override
    public boolean resourceExists(String path) {
        return getClass().getClassLoader().getResource(path) != null;
    }

    @Override
    public InputStream getResourceAsStream(String path) {
        return getClass().getClassLoader().getResourceAsStream(path);
    }
}

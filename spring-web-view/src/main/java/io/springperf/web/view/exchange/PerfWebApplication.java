package io.springperf.web.view.exchange;

import org.thymeleaf.web.IWebApplication;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 基于 classpath 的 {@link IWebApplication} 适配（零 Servlet 依赖）。
 * <p>
 * 属性存于本实例的 map（应用级共享需由调用方持有同一实例）； 资源读取走类加载器。
 * </p>
 *
 * @since 3.5.7
 */
public class PerfWebApplication implements IWebApplication {

    /** 懒创建：绝大多数渲染不会读写 application 属性，避免每次 exchange 都分配一个空 Map。 */
    private Map<String, Object> attrs;
    /** 只读视图缓存：首次创建 attrs 时一并生成，后续重复调用不再包装。 */
    private Set<String> allAttributeNamesView;

    private Map<String, Object> attributes() {
        if (attrs == null) {
            attrs = new HashMap<>(4);
            allAttributeNamesView = java.util.Collections.unmodifiableSet(attrs.keySet());
        }
        return attrs;
    }

    @Override
    public boolean containsAttribute(String name) {
        return attrs != null && attrs.containsKey(name);
    }

    @Override
    public int getAttributeCount() {
        return attrs != null ? attrs.size() : 0;
    }

    @Override
    public Set<String> getAllAttributeNames() {
        return attrs != null ? allAttributeNamesView : java.util.Collections.emptySet();
    }

    @Override
    public Map<String, Object> getAttributeMap() {
        return attributes();
    }

    @Override
    public Object getAttributeValue(String name) {
        return attrs != null ? attrs.get(name) : null;
    }

    @Override
    public void setAttributeValue(String name, Object value) {
        attributes().put(name, value);
    }

    @Override
    public void removeAttribute(String name) {
        if (attrs != null) {
            attrs.remove(name);
        }
    }

    @Override
    public boolean resourceExists(String path) {
        return getClass().getClassLoader().getResource(path) != null;
    }

    @Override
    public InputStream getResourceAsStream(String path) {
        return getClass().getClassLoader().getResourceAsStream(path);
    }
}

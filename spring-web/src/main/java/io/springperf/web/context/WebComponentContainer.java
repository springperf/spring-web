package io.springperf.web.context;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

@Slf4j
public class WebComponentContainer extends BaseWebComponent {

    protected final AtomicReference<State> state = new AtomicReference<>(State.NEW);

    /**
     * web组件容器
     */
    protected Map<String, WebComponent> webComponents = new HashMap<>();

    /**
     * 添加自动从spring中自动注册的机制
     */
    protected Map<Class, Function<?, ? extends WebComponent>> autoRegisterComponentMap = new HashMap<>();

    public <T extends WebComponent> T getWebComponent(Class<T> clazz) {
        List<T> list = getWebComponents(clazz);
        return list.isEmpty() ? null : list.get(0);
    }

    public <T extends WebComponent> List<T> getWebComponents(Class<T> clazz) {
        List<T> list = new ArrayList<>();
        for (WebComponent component : webComponents.values()) {
            if (clazz.isAssignableFrom(component.getClass())) {
                list.add((T) component);
            }
        }
        AnnotationAwareOrderComparator.sort(list);
        return list;
    }

    public <T extends WebComponent> T getWebComponentWithDefault(Class<T> clazz, T defaultComponent) {
        T component = getWebComponent(clazz);
        if (component == null) {
            registerWebComponent(clazz);
            component = getWebComponent(clazz);
            if (component == null) {
                registerWebComponent(defaultComponent);
                component = defaultComponent;
            }
        }
        return component;
    }

    public void autoRegisterWebComponent(Class<? extends WebComponent> clazz) {
        autoRegisterComponentMap.put(clazz, Function.identity());
    }

    public <B> void autoRegisterWebComponent(Class<B> clazz, Function<B, ? extends WebComponent> getComponentFunc) {
        autoRegisterComponentMap.put(clazz, getComponentFunc);
    }

    protected <T extends WebComponent> void initRealComponentList(List<T> list, Class<T> clazz) {
        list.clear();
        list.addAll(getWebComponents(clazz));
    }

    protected <T extends WebComponent> void registerWebComponent(Class<T> clazz) {
        Map<String, T> beanMap = webContext.getCtx().getBeansOfType(clazz);
        for (T bean : beanMap.values()) {
            registerWebComponent(bean);
        }
    }

    protected <B, T extends WebComponent> void registerWebComponent(Class<B> clazz, Function<B, T> getComponentFunc) {
        Map<String, B> beanMap = webContext.getCtx().getBeansOfType(clazz);
        for (B bean : beanMap.values()) {
            T component = getComponentFunc.apply(bean);
            if (component != null) {
                registerWebComponent(component);
            }
        }
    }

    @SneakyThrows
    public void registerWebComponent(WebComponent webComponent) {
        if (webComponents.containsKey(webComponent.getComponentName())) {
            WebComponent oldComponent = webComponents.get(webComponent.getComponentName());
            List<WebComponent> list = Arrays.asList(webComponent, oldComponent);
            AnnotationAwareOrderComparator.sort(list);
            WebComponent newComponent = list.get(0);
            webComponents.put(webComponent.getComponentName(), newComponent);
            log.warn("{} components have conflicts. Use {} and deprecate {}", webComponent.getComponentName(), newComponent, list.get(1));
            if (newComponent == webComponent) {
                destroyComponent(oldComponent);
            } else {
                return;
            }
        } else {
            webComponents.put(webComponent.getComponentName(), webComponent);
        }

        if (state.get() == State.INIT_CONTEXT || state.get() == State.PHASE1 || state.get() == State.PHASE2 || state.get() == State.PHASE3) {
            webComponent.initWithWebContext(webContext);
        }
        if (state.get() == State.PHASE1 || state.get() == State.PHASE2 || state.get() == State.PHASE3) {
            initComponentPhase1(webComponent);
        }
        if (state.get() == State.PHASE2 || state.get() == State.PHASE3) {
            initComponentPhase2(webComponent);
        }
        if (state.get() == State.PHASE3) {
            initComponentPhase3(webComponent);
        }
        if (state.get() == State.DESTROY) {
            destroyComponent(webComponent);
        }
    }

    protected List<WebComponent> getSortedWebComponents() {
        List<WebComponent> sortedWebComponents = new ArrayList<>(webComponents.values());
        AnnotationAwareOrderComparator.sort(sortedWebComponents);
        return sortedWebComponents;
    }

    @Override
    public void initWithWebContext(WebContext webContext) {
        if (!state.compareAndSet(State.NEW, State.INIT_CONTEXT)) {
            return;
        }
        super.initWithWebContext(webContext);
        for (Map.Entry<Class, Function<?, ? extends WebComponent>> entry : autoRegisterComponentMap.entrySet()) {
            registerWebComponent(entry.getKey(), entry.getValue());
        }
        for (WebComponent component : getSortedWebComponents()) {
            component.initWithWebContext(webContext);
        }
    }

    @Override
    public void initComponentPhase1() throws Exception {
        if (!state.compareAndSet(State.INIT_CONTEXT, State.PHASE1)) {
            return;
        }
        super.initComponentPhase1();
        for (WebComponent component : getSortedWebComponents()) {
            initComponentPhase1(component);
        }
    }

    @Override
    public void initComponentPhase2() throws Exception {
        if (!state.compareAndSet(State.PHASE1, State.PHASE2)) {
            return;
        }
        super.initComponentPhase2();
        for (WebComponent component : getSortedWebComponents()) {
            initComponentPhase2(component);
        }
    }

    @Override
    public void initComponentPhase3() throws Exception {
        if (!state.compareAndSet(State.PHASE2, State.PHASE3)) {
            return;
        }
        super.initComponentPhase3();
        for (WebComponent component : getSortedWebComponents()) {
            initComponentPhase3(component);
        }
    }

    @Override
    public void destroyComponent() throws Exception {
        if (!state.compareAndSet(State.PHASE3, State.DESTROY)) {
            return;
        }
        super.destroyComponent();
        for (WebComponent component : getSortedWebComponents()) {
            try {
                destroyComponent(component);
            } catch (Exception e) {
                log.error("{} destroyComponent fail", component.getComponentName(), e);
            }
        }
    }

    private void initComponentPhase1(WebComponent component) throws Exception {
        if (component instanceof LifecycleWebComponent) {
            ((LifecycleWebComponent) component).initComponentPhase1();
        }
    }

    private void initComponentPhase2(WebComponent component) throws Exception {
        if (component instanceof LifecycleWebComponent) {
            ((LifecycleWebComponent) component).initComponentPhase2();
        }
    }

    private void initComponentPhase3(WebComponent component) throws Exception {
        if (component instanceof LifecycleWebComponent) {
            ((LifecycleWebComponent) component).initComponentPhase3();
        }
    }

    private void destroyComponent(WebComponent component) throws Exception {
        if (component instanceof LifecycleWebComponent) {
            ((LifecycleWebComponent) component).destroyComponent();
        }
    }

    private enum State {
        NEW,
        INIT_CONTEXT,
        PHASE1,
        PHASE2,
        PHASE3,
        DESTROY
    }
}

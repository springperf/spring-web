package io.springperf.web.context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WebComponentContainer extends BaseWebComponent {

    protected final AtomicReference<State> state = new AtomicReference<>(State.NEW);

    /**
     * web组件容器
     * <p>
     * 并发安全：启动期单线程注册 + 运行期动态注册（如 Actuator 端点）可能并发， 使用 ConcurrentHashMap 避免请求路径遍历读与动态注册写的竞争。
     * </p>
     */
    protected Map<String, WebComponent> webComponents = new ConcurrentHashMap<>();

    /**
     * 添加自动从spring中自动注册的机制
     */
    protected Map<Class, Function<?, ? extends WebComponent>> autoRegisterComponentMap = new ConcurrentHashMap<>();

    public <T extends WebComponent> T getWebComponent(Class<T> clazz) {
        // 快路径：唯一命中时直接返回，跳过 List 分配与排序。
        // 本方法在每请求路径上被反复调用（servlet 桥的 PerfHttpServletRequest/Response
        // 构造期即调用，见 PerfHttpServletResponse#applyContainerResponseEncoding），
        // 原实现每次都 new ArrayList + 遍历全部组件 + AnnotationAwareOrderComparator.sort，
        // 而结果只取 get(0)。实测 json 场景服务端分配 5,724 → 5,446 B/op（-278 B，-4.9%）。
        //
        // 语义等价性：单命中时排序是 no-op，先行返回与「排序后取 get(0)」结果相同。
        // 多命中时仍走原排序路径，保证 @Order 语义不变。
        T first = null;
        int hits = 0;
        for (WebComponent component : webComponents.values()) {
            if (clazz.isAssignableFrom(component.getClass())) {
                if (first == null) {
                    first = (T) component;
                }
                hits++;
            }
        }
        if (hits == 1) {
            return first;
        }
        if (hits == 0) {
            return null;
        }
        // 多命中：按精确容量收集后排序，保留既有「排序取首」语义
        List<T> list = new ArrayList<>(hits);
        for (WebComponent component : webComponents.values()) {
            if (clazz.isAssignableFrom(component.getClass())) {
                list.add((T) component);
            }
        }
        AnnotationAwareOrderComparator.sort(list);
        return list.get(0);
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
        String componentName = webComponent.getComponentName();
        if (componentName == null) {
            componentName = webComponent.getClass().getSimpleName();
        }
        final String name = componentName;
        // compute 原子合并：containsKey/get/put 合一，避免并发注册同名组件时的竞态（误 destroy / 丢失组件）
        // compute 返回最终保留的组件：若命名冲突且旧组件胜出（该入参组件落败），下方跳过生命周期初始化，
        // 避免落败组件产生副作用（重复注册路由/扫描）且永不销毁的资源泄漏。
        WebComponent kept = webComponents.compute(name, (key, oldComponent) -> {
            if (oldComponent != null) {
                List<WebComponent> list = Arrays.asList(webComponent, oldComponent);
                AnnotationAwareOrderComparator.sort(list);
                WebComponent newComponent = list.get(0);
                log.warn("{} components have conflicts. Use {} and deprecate {}", name, newComponent, list.get(1));
                if (newComponent == webComponent) {
                    destroyOldComponentSneaky(oldComponent);
                    return webComponent;
                }
                return oldComponent;
            }
            return webComponent;
        });

        if (kept != webComponent) {
            // 命名冲突且本组件落败：未进入容器，不做生命周期初始化，避免副作用（如重复注册路由）
            return;
        }
        if (state.get() == State.INIT_CONTEXT || state.get() == State.PHASE1 || state.get() == State.PHASE2
                || state.get() == State.PHASE3) {
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

    /**
     * 销毁所有子组件并置状态为 {@link State#DESTROY}。
     * <p>
     * 允许从任意已初始化状态（INIT_CONTEXT/PHASE1/PHASE2/PHASE3）进入销毁： 生命周期中途失败（如 startLifecycle 某 phase 抛异常）时也能清理已初始化的组件，
     * 而不是停留在中间态无法回收资源。
     * </p>
     */
    @Override
    public void destroyComponent() throws Exception {
        State current = state.get();
        if (current == State.NEW || current == State.DESTROY) {
            return;
        }
        if (!state.compareAndSet(current, State.DESTROY)) {
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

    /**
     * 状态机是否已处于 DESTROY（destroy 或启动失败清理后）。 用于支持 stop/restart 场景下重新初始化。
     */
    protected boolean isDestroyed() {
        return state.get() == State.DESTROY;
    }

    /**
     * 将状态机从 DESTROY 复位回 NEW，使组件可重新执行完整生命周期（stop/restart 支持）。
     * <p>
     * 递归复位所有子容器：destroy 会把子组件一并置为 DESTROY， 仅复位自身会令子组件后续 {@code init*} 的 CAS 全部失败（静默跳过初始化、路由表为空）， 因此必须同步复位嵌套容器，destroy
     * 后才可真正重新 start。
     * </p>
     *
     * @return 是否成功复位；非 DESTROY 状态返回 false（无需复位）
     */
    protected boolean resetAfterDestroy() {
        boolean reset = state.compareAndSet(State.DESTROY, State.NEW);
        if (reset) {
            for (WebComponent component : getSortedWebComponents()) {
                if (component instanceof WebComponentContainer) {
                    ((WebComponentContainer) component).resetAfterDestroy();
                }
            }
        }
        return reset;
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

    /** 供 {@code ConcurrentHashMap.compute} lambda 内调用，抹平受检异常。 */
    @SneakyThrows
    private void destroyOldComponentSneaky(WebComponent component) {
        destroyComponent(component);
    }

    private enum State {
        NEW, INIT_CONTEXT, PHASE1, PHASE2, PHASE3, DESTROY
    }
}

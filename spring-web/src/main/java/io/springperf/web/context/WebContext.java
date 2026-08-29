package io.springperf.web.context;

import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.util.WebUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.ObjectUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central web application context that holds the Spring {@link ApplicationContext},
 * configuration properties, and manages the lifecycle of all web components
 * (dispatcher handler, interceptors, converters, etc.).
 * <p>
 * Initialization proceeds in three phases to respect component ordering dependencies.
 */
@Data
@Slf4j
public class WebContext extends WebComponentContainer implements DisposableBean, ApplicationContextAware {

    private String contextPath;
    private ApplicationContext ctx;
    private ApplicationProperties props;

    private DispatcherHandler dispatcherHandler;

    private final AtomicBoolean lifecycleStarted = new AtomicBoolean(false);

    public WebContext(DispatcherHandler dispatcherHandler, ApplicationProperties props) {
        this.dispatcherHandler = dispatcherHandler;
        this.props = props;
        this.contextPath = WebUtils.formatPath(props.get(PropertiesConstant.CONTEXT_PATH, "/"));
        this.webContext = this;
        registerWebComponent(dispatcherHandler);
    }

    /**
     * Trigger the full WebComponent lifecycle: init contexts, then Phase 1/2/3.
     * <p>Called from {@link io.springperf.web.server.NettyHttpServer#start()} after
     * the Spring context is fully loaded and all beans (including the bridge)
     * have been registered as WebComponents.</p>
     * <p>失败时清理已初始化的组件并复位 {@link #lifecycleStarted}，使启动失败后可以重试；
     * 已 {@link #destroy()} 过的实例可通过再次调用本方法重新启动生命周期。</p>
     */
    public void startLifecycle() {
        // 支持 destroy 后重新 start：状态机停留在 DESTROY，需复位到 NEW 才能重新初始化
        if (isDestroyed()) {
            resetAfterDestroy();
        }
        if (!lifecycleStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            this.initWithWebContext(this);
            this.initComponentPhase1();
            this.initComponentPhase2();
            this.initComponentPhase3();
        } catch (Exception e) {
            // 启动失败：清理已初始化的组件（State 可能停在中间态，见 destroyComponent），
            // 并复位 lifecycleStarted，保证同一实例可重试启动。fail-fast 语义保留（rethrow）。
            try {
                this.destroyComponent();
            } catch (Exception cleanupEx) {
                log.warn("WebContext cleanup after failed lifecycle start also failed", cleanupEx);
            }
            lifecycleStarted.set(false);
            throw new RuntimeException("Failed to start WebContext lifecycle", e);
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.ctx = applicationContext;
    }

    @Override
    public void destroy() throws Exception {
        this.destroyComponent();
        // 复位生命周期标记，支持 stop/restart 场景下再次 startLifecycle 重新初始化
        this.lifecycleStarted.set(false);
    }

    /**
     * Retrieves the highest-priority bean of the specified type from the Spring {@link ApplicationContext}.
     *
     * @param clazz the bean type to look up
     * @param <T>   the bean type
     * @return the bean, or {@code null} if no bean of the given type exists
     */
    public <T> T getBeanFromCtx(Class<T> clazz) {
        Map<String, T> beanMap = getWebContext().getCtx().getBeansOfType(clazz);
        if (ObjectUtils.isEmpty(beanMap)) {
            return null;
        }
        List<T> list = new ArrayList<>(beanMap.values());
        AnnotationAwareOrderComparator.sort(list);
        return list.get(0);
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE;
    }
}

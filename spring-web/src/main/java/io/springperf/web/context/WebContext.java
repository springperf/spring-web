package io.springperf.web.context;

import io.springperf.web.core.DispatcherHandler;
import lombok.Data;
import io.springperf.web.util.WebUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.ObjectUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Central web application context that holds the Spring {@link ApplicationContext},
 * configuration properties, and manages the lifecycle of all web components
 * (dispatcher handler, interceptors, converters, etc.).
 * <p>
 * Initialization proceeds in three phases to respect component ordering dependencies.
 */
@Data
public class WebContext extends WebComponentContainer implements InitializingBean, DisposableBean, ApplicationContextAware {

    private String contextPath;
    private ApplicationContext ctx;
    private ApplicationProperties props;

    private DispatcherHandler dispatcherHandler;

    public WebContext(DispatcherHandler dispatcherHandler, ApplicationProperties props) {
        this.dispatcherHandler = dispatcherHandler;
        this.props = props;
        this.contextPath = WebUtils.formatPath(props.get(PropertiesConstant.CONTEXT_PATH, "/"));
        registerWebComponent(dispatcherHandler);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        this.initWithWebContext(this);
        this.initComponentPhase1();
        this.initComponentPhase2();
        this.initComponentPhase3();
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.ctx = applicationContext;
    }

    @Override
    public void destroy() throws Exception {
        this.destroyComponent();
    }

    /**
     * Retrieves the highest-priority bean of the specified type from the Spring {@link ApplicationContext}.
     *
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

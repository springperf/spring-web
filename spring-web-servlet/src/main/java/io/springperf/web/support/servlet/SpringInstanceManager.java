package io.springperf.web.support.servlet;

import org.apache.tomcat.InstanceManager;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.Objects;

/**
 * 把 Jasper 的实例化/销毁交给 Spring 容器。
 * <p>
 * Jasper 在编译 JSP / tag 文件后用 {@link InstanceManager} 创建这些生成类的实例。默认的 {@code SimpleInstanceManager} 只做裸反射构造，JSP 里声明的
 * {@code @Autowired} 依赖全部为 null。本实现改为经 {@link AutowireCapableBeanFactory#createBean(Class)} 创建，从而获得与普通 bean 一致的语义：依赖注入、
 * {@code @PostConstruct} 等初始化回调，以及销毁时的 {@code @PreDestroy} 回调。
 * </p>
 * <p>
 * 注意：JSP 生成的类不是 bean 定义，每次 JSP 首次加载时按类实例化一次（Jasper 自行缓存）， 因此这里的生命周期开销只发生在编译后的首次实例化，不在请求热路径上。
 * </p>
 */
public final class SpringInstanceManager implements InstanceManager {

    private final AutowireCapableBeanFactory beanFactory;

    /**
     * @param beanFactory
     *            Spring 的 {@link AutowireCapableBeanFactory}，通常来自
     *            {@code ApplicationContext#getAutowireCapableBeanFactory()}
     */
    public SpringInstanceManager(AutowireCapableBeanFactory beanFactory) {
        this.beanFactory = Objects.requireNonNull(beanFactory, "AutowireCapableBeanFactory must not be null");
    }

    @Override
    public Object newInstance(Class<?> clazz)
            throws IllegalAccessException, InvocationTargetException, javax.naming.NamingException,
            InstantiationException, IllegalArgumentException, NoSuchMethodException, SecurityException {
        if (clazz == null) {
            throw new IllegalArgumentException("Class must not be null");
        }
        try {
            return beanFactory.createBean(clazz);
        } catch (BeansException e) {
            // Jasper 的契约要求受检异常；把 Spring 的失败原样带出（不让 JSP 以"已实例化但依赖为空"运行）
            InstantiationException failure = new InstantiationException(
                    "Spring failed to instantiate " + clazz.getName() + ": " + e.getMessage());
            failure.initCause(e);
            throw failure;
        }
    }

    @Override
    public Object newInstance(String className) throws IllegalAccessException, InvocationTargetException,
            javax.naming.NamingException, InstantiationException, ClassNotFoundException, IllegalArgumentException,
            NoSuchMethodException, SecurityException {
        return newInstance(className, Thread.currentThread().getContextClassLoader());
    }

    @Override
    public Object newInstance(String fqcn, ClassLoader classLoader) throws IllegalAccessException,
            InvocationTargetException, javax.naming.NamingException, InstantiationException, ClassNotFoundException,
            IllegalArgumentException, NoSuchMethodException, SecurityException {
        if (fqcn == null) {
            throw new IllegalArgumentException("Class name must not be null");
        }
        ClassLoader loader = classLoader != null ? classLoader : getClass().getClassLoader();
        // initialize=true：与 Tomcat DefaultInstanceManager 一致，类初始化失败在此显形
        return newInstance(Class.forName(fqcn, true, loader));
    }

    /**
     * 对「已构造完成」的实例补一次依赖注入（Jasper 用该入口处理它自己 new 出来的对象）。
     */
    @Override
    public void newInstance(Object o)
            throws IllegalAccessException, InvocationTargetException, javax.naming.NamingException {
        if (o == null) {
            throw new IllegalArgumentException("Instance must not be null");
        }
        beanFactory.autowireBean(o);
    }

    @Override
    public void destroyInstance(Object o) throws IllegalAccessException, InvocationTargetException {
        if (o != null) {
            beanFactory.destroyBean(o);
        }
    }
}

package io.springperf.web.support.servlet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link SpringInstanceManager} 的契约：实例化走 Spring（含生命周期回调），销毁回调 Spring，其余入口严格委派。
 */
class SpringInstanceManagerTest {

    /** 公开无参构造器 + 生命周期回调，用于验证 createBean / destroyBean 的真实效果。 */
    public static class LifecyclePojo implements InitializingBean, DisposableBean {
        boolean initialized;
        boolean destroyed;

        @Override
        public void afterPropertiesSet() {
            initialized = true;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }
    }

    /** 没有公共无参构造器：Spring 无法实例化，必须显形失败而不是返回半成品。 */
    public static class NoDefaultConstructor {
        public NoDefaultConstructor(String ignored) {
        }
    }

    @Test
    void newInstance_viaClass_runsSpringLifecycle() throws Exception {
        SpringInstanceManager manager = new SpringInstanceManager(new DefaultListableBeanFactory());

        Object instance = manager.newInstance(LifecyclePojo.class);

        assertInstanceOf(LifecyclePojo.class, instance);
        assertTrue(((LifecyclePojo) instance).initialized, "createBean 应触发 afterPropertiesSet");
    }

    @Test
    void newInstance_viaClassName_loadsAndCreates() throws Exception {
        SpringInstanceManager manager = new SpringInstanceManager(new DefaultListableBeanFactory());

        Object instance = manager.newInstance(LifecyclePojo.class.getName());

        assertInstanceOf(LifecyclePojo.class, instance);
    }

    @Test
    void newInstance_viaClassNameWithExplicitClassLoader_usesThatLoader() throws Exception {
        SpringInstanceManager manager = new SpringInstanceManager(new DefaultListableBeanFactory());

        Object instance = manager.newInstance(LifecyclePojo.class.getName(), getClass().getClassLoader());

        assertInstanceOf(LifecyclePojo.class, instance);
    }

    @Test
    void newInstance_unknownClass_throwsClassNotFoundException() {
        SpringInstanceManager manager = new SpringInstanceManager(new DefaultListableBeanFactory());
        assertThrows(ClassNotFoundException.class, () -> manager.newInstance("no.such.Listener"));
    }

    @Test
    void newInstance_classNameNull_throwsIllegalArgument() {
        SpringInstanceManager manager = new SpringInstanceManager(new DefaultListableBeanFactory());
        assertThrows(IllegalArgumentException.class, () -> manager.newInstance((String) null));
    }

    @Test
    void newInstance_classWithoutDefaultConstructor_wrappedAsInstantiationException() {
        SpringInstanceManager manager = new SpringInstanceManager(new DefaultListableBeanFactory());

        InstantiationException failure = assertThrows(InstantiationException.class,
                () -> manager.newInstance(NoDefaultConstructor.class));

        assertTrue(failure.getMessage().contains(NoDefaultConstructor.class.getName()), "异常必须带上下文（类名），否则 JSP 编译失败无从定位");
        assertNotNull(failure.getCause());
    }

    @Test
    void newInstance_existingInstance_delegatesToAutowire() throws Exception {
        AutowireCapableBeanFactory beanFactory = mock(AutowireCapableBeanFactory.class);
        SpringInstanceManager manager = new SpringInstanceManager(beanFactory);
        Object existing = new Object();

        manager.newInstance(existing);

        verify(beanFactory).autowireBean(existing);
    }

    @Test
    void newInstance_existingInstanceNull_throwsIllegalArgument() {
        SpringInstanceManager manager = new SpringInstanceManager(mock(AutowireCapableBeanFactory.class));
        assertThrows(IllegalArgumentException.class, () -> manager.newInstance((Object) null));
    }

    @Test
    void destroyInstance_runsSpringDestroyCallback() throws Exception {
        LifecyclePojo pojo = new LifecyclePojo();

        new SpringInstanceManager(new DefaultListableBeanFactory()).destroyInstance(pojo);

        assertTrue(pojo.destroyed, "destroyBean 应触发 destroy() 回调");
    }

    @Test
    void destroyInstance_null_isNoOp() throws Exception {
        AutowireCapableBeanFactory beanFactory = mock(AutowireCapableBeanFactory.class);

        new SpringInstanceManager(beanFactory).destroyInstance(null);

        verifyNoInteractions(beanFactory);
    }

    @Test
    void constructor_nullBeanFactory_throwsNullPointer() {
        assertThrows(NullPointerException.class, () -> new SpringInstanceManager(null));
    }
}

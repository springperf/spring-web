package io.springperf.web.core.arg.databinder;

import org.junit.jupiter.api.Test;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.support.WebBindingInitializer;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class PerfDataBinderFactoryTest {

    @Test void createBinderInstance_returnsPerfDataBinder() throws Exception {
        PerfDataBinderFactory factory = new PerfDataBinderFactory(Collections.emptyList(), null);
        PerfDataBinder binder = (PerfDataBinder) factory.createBinderInstance(new Object(), "test", null);
        assertNotNull(binder);
        assertEquals("test", binder.getObjectName());
    }

    @Test void createBinderInstance_targetIsPreserved() throws Exception {
        Object target = new Object();
        PerfDataBinderFactory factory = new PerfDataBinderFactory(Collections.emptyList(), null);
        PerfDataBinder binder = (PerfDataBinder) factory.createBinderInstance(target, "obj", null);
        assertSame(target, binder.getTarget());
    }

    @Test void createBinderInstance_withInitializer_usesInitializer() throws Exception {
        WebBindingInitializer initializer = factory -> factory.setValidator(new LocalValidatorFactoryBean());
        PerfDataBinderFactory factory = new PerfDataBinderFactory(Collections.emptyList(), initializer);
        PerfDataBinder binder = (PerfDataBinder) factory.createBinderInstance(new Object(), "test", null);
        assertNotNull(binder);
    }
}
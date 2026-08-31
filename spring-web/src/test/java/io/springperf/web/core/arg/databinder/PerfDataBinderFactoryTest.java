package io.springperf.web.core.arg.databinder;

import org.junit.jupiter.api.Test;
import org.springframework.validation.Validator;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.support.WebBindingInitializer;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test void createBinder_appliesInitializer() throws Exception {
        // Initializer 通过 createBinder()（InitBinderDataBinderFactory 流程）应用，
        // 而非 createBinderInstance()：绑定 validator 后通过 binder.getValidator() 可见
        final Validator validator = mock(Validator.class);
        when(validator.supports(any())).thenReturn(true);
        WebBindingInitializer initializer = binder -> binder.setValidator(validator);
        PerfDataBinderFactory factory = new PerfDataBinderFactory(Collections.emptyList(), initializer);

        WebDataBinder binder = factory.createBinder(null, new Object(), "test");

        assertNotNull(binder);
        assertSame(validator, binder.getValidator(), "createBinder 应应用全局 WebBindingInitializer");
    }
}
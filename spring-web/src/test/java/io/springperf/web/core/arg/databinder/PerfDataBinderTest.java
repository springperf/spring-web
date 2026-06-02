package io.springperf.web.core.arg.databinder;

import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.web.bind.WebDataBinder;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PerfDataBinderTest {

    @Mock WebServerHttpRequest request;

    @Test void constructor_setsFieldDefaultPrefixToNull() {
        PerfDataBinder binder = new PerfDataBinder(new Object(), "test");
        assertNull(binder.getFieldDefaultPrefix());
    }

    @Test void constructor_setsFieldMarkerPrefixToNull() {
        PerfDataBinder binder = new PerfDataBinder(new Object(), "test");
        assertNull(binder.getFieldMarkerPrefix());
    }

    @Test void bind_withPerfDataBinder_callsBindMethod() {
        PerfDataBinder binder = spy(new PerfDataBinder(new TestBean(), "test"));
        when(request.getParameterMapArray()).thenReturn(new HashMap<>());
        PerfDataBinder.bind(request, binder);
        verify(binder).bind(request);
    }

    @Test void bind_withNonPerfDataBinder_callsStandardBind() {
        WebDataBinder standardBinder = spy(new WebDataBinder(new TestBean(), "test"));
        when(request.getParameterMapArray()).thenReturn(new HashMap<>());
        PerfDataBinder.bind(request, standardBinder);
        verify(standardBinder).bind(any(MutablePropertyValues.class));
    }

    @Test void bind_parametersAreApplied() {
        TestBean bean = new TestBean();
        PerfDataBinder binder = new PerfDataBinder(bean, "test");
        when(request.getParameterMapArray()).thenReturn(new HashMap<String, String[]>() {{
            put("name", new String[]{"test-name"});
        }});
        binder.bind(request);
        assertEquals("test-name", bean.getName());
    }

    @Test void addBindValues_isExtensionHook() {
        PerfDataBinder binder = new PerfDataBinder(new TestBean(), "test") {
            @Override protected void addBindValues(MutablePropertyValues mpvs, WebServerHttpRequest request) {
                mpvs.add("name", "hook-value");
            }
        };
        when(request.getParameterMapArray()).thenReturn(new HashMap<>());
        binder.bind(request);
        assertEquals("hook-value", ((TestBean) binder.getTarget()).getName());
    }

    @Test void bind_withNullMultiFileMap_doesNotThrow() {
        TestBean bean = new TestBean();
        PerfDataBinder binder = new PerfDataBinder(bean, "test");
        when(request.getParameterMapArray()).thenReturn(new HashMap<>());
        when(request.getMultiFileMap()).thenReturn(null);
        assertDoesNotThrow(() -> binder.bind(request));
    }

    static class TestBean {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
}
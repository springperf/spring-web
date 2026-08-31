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

    @Test void bind_withMultipartFileMap_bindsFileToProperty() {
        // 覆盖 bind() 的 bindMultipart 分支（PerfDataBinder.java:27-29）：
        // multipart 文件按属性名绑定到目标 bean 的 MultipartFile 字段
        MultipartBean bean = new MultipartBean();
        PerfDataBinder binder = new PerfDataBinder(bean, "test");
        when(request.getParameterMapArray()).thenReturn(new HashMap<>());
        org.springframework.util.LinkedMultiValueMap<String, org.springframework.web.multipart.MultipartFile> fileMap =
                new org.springframework.util.LinkedMultiValueMap<>();
        org.springframework.web.multipart.MultipartFile file = mock(org.springframework.web.multipart.MultipartFile.class);
        fileMap.add("file", file);
        when(request.getMultiFileMap()).thenReturn(fileMap);

        binder.bind(request);

        assertSame(file, bean.getFile(), "multipart 文件应绑定到同名属性");
    }

    static class TestBean {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    static class MultipartBean {
        private org.springframework.web.multipart.MultipartFile file;
        public org.springframework.web.multipart.MultipartFile getFile() { return file; }
        public void setFile(org.springframework.web.multipart.MultipartFile file) { this.file = file; }
    }
}
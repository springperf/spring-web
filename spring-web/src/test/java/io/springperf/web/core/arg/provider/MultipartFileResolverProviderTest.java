package io.springperf.web.core.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MultipartFileResolverProviderTest {

    MultipartFileResolverProvider provider = new MultipartFileResolverProvider();

    @Test
    void supports_multipartFileType_returnsTrue() throws Exception {
        Method method = getClass().getMethod("fileParam", MultipartFile.class);
        MethodParameter mp = new MethodParameter(method, 0);
        assertTrue(provider.supports(mp, null));
    }

    @Test
    void supports_multipartFileArrayType_returnsTrue() throws Exception {
        Method method = getClass().getMethod("fileArrayParam", MultipartFile[].class);
        MethodParameter mp = new MethodParameter(method, 0);
        assertTrue(provider.supports(mp, null));
    }

    @Test
    void supports_nonMultipartFileType_returnsFalse() throws Exception {
        Method method = getClass().getMethod("stringParam", String.class);
        MethodParameter mp = new MethodParameter(method, 0);
        assertFalse(provider.supports(mp, null));
    }

    @Test
    void order_isNegative10000() {
        assertEquals(-10000, provider.getOrder());
    }

    @Test
    void supportType_isMultipartFile() {
        assertEquals(MultipartFile.class, provider.supportType());
    }

    @Test
    void getMultiValueMapResolver_returnsRequestMultiFileMap() throws Exception {
        // 核心行为：MultiValueMapResolver 返回 request.getMultiFileMap()（MultipartFileResolverProvider.java:14）
        MethodParameter mp = new MethodParameter(getClass().getMethod("fileParam", MultipartFile.class), 0);
        mp.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());
        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        RequestContext requestContext = mock(RequestContext.class);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        WebContext webContext = mock(WebContext.class);
        MappingHandlerMethod mappingContext = mock(MappingHandlerMethod.class);
        when(request.getRequestContext()).thenReturn(requestContext);
        Map<RequestAttribute<?>, Object> attrs = new HashMap<>();
        when(requestContext.getAttribute(any(RequestAttribute.class)))
                .thenAnswer(inv -> attrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(requestContext).setAttribute(any(RequestAttribute.class), any());

        LinkedMultiValueMap<String, MultipartFile> fileMap = new LinkedMultiValueMap<>();
        MultipartFile file = mock(MultipartFile.class);
        fileMap.add("file", file);
        when(request.getMultiFileMap()).thenReturn(fileMap);

        StaticArgumentResolver resolver = provider.getResolver(mp, mappingContext, webContext);
        Object result = resolver.resolveArgument(request, response);

        assertSame(file, result, "单值 MultipartFile 参数应从 multiFileMap 中按参数名取回");
    }

    @Test
    void getMultiValueMapResolver_arrayParam_returnsMapValues() throws Exception {
        MethodParameter mp = new MethodParameter(getClass().getMethod("fileArrayParam", MultipartFile[].class), 0);
        mp.initParameterNameDiscovery(new org.springframework.core.DefaultParameterNameDiscoverer());
        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        WebContext webContext = mock(WebContext.class);
        MappingHandlerMethod mappingContext = mock(MappingHandlerMethod.class);
        // 数组转换路径需要 WebDataBinderRegistry 提供 ConversionService
        io.springperf.web.core.arg.databinder.WebDataBinderRegistry binderRegistry =
                new io.springperf.web.core.arg.databinder.WebDataBinderRegistry();
        try {
            java.lang.reflect.Field field = io.springperf.web.core.arg.databinder.WebDataBinderRegistry.class
                    .getDeclaredField("defaultConversionService");
            field.setAccessible(true);
            field.set(binderRegistry, new org.springframework.format.support.DefaultFormattingConversionService());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(webContext.getWebComponent(io.springperf.web.core.arg.databinder.WebDataBinderRegistry.class))
                .thenReturn(binderRegistry);

        LinkedMultiValueMap<String, MultipartFile> fileMap = new LinkedMultiValueMap<>();
        MultipartFile f1 = mock(MultipartFile.class);
        MultipartFile f2 = mock(MultipartFile.class);
        fileMap.add("files", f1);
        fileMap.add("files", f2);
        when(request.getMultiFileMap()).thenReturn(fileMap);
        when(request.getRequestContext()).thenReturn(mock(RequestContext.class));

        StaticArgumentResolver resolver = provider.getResolver(mp, mappingContext, webContext);
        Object resolved = resolver.resolveArgument(request, response);

        MultipartFile[] result;
        if (resolved instanceof MultipartFile[]) {
            result = (MultipartFile[]) resolved;
        } else {
            java.util.List<MultipartFile> list = (java.util.List<MultipartFile>) resolved;
            result = list.toArray(new MultipartFile[0]);
        }
        assertEquals(2, result.length, "数组参数应取回 multiFileMap 中同名的全部文件");
        assertSame(f1, result[0]);
        assertSame(f2, result[1]);
    }

    @SuppressWarnings("unused")
    public void fileParam(MultipartFile file) {}

    @SuppressWarnings("unused")
    public void fileArrayParam(MultipartFile[] files) {}

    @SuppressWarnings("unused")
    public void stringParam(String s) {}
}

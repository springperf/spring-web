package io.springperf.web.core.arg.resolver;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelAttributeResolverTest {

    @Mock
    WebContext webContext;

    @Mock
    WebDataBinderFactory binderFactory;

    @Mock
    MappingHandlerMethod mappingContext;

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    @Mock
    WebDataBinder dataBinder;

    @Mock
    RequestContext requestContext;

    private void stubRequestContext() {
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(any(RequestAttribute.class))).thenReturn(null);
    }

    @Test
    void constructAttribute_createsInstance() throws Exception {
        Method method = getClass().getMethod("modelParam", ModelAttrBean.class);
        MethodParameter mp = new MethodParameter(method, 0);

        ModelAttributeResolver resolver = new ModelAttributeResolver(
                binderFactory, webContext, mappingContext, mp, "modelParam", true);

        Object attr = resolver.constructAttribute();
        assertNotNull(attr);
        assertInstanceOf(ModelAttrBean.class, attr);
    }

    @Test
    void initialConstructor_usesDefaultConstructor() throws Exception {
        Method method = getClass().getMethod("modelParam", ModelAttrBean.class);
        MethodParameter mp = new MethodParameter(method, 0);

        ModelAttributeResolver resolver = new ModelAttributeResolver(
                binderFactory, webContext, mappingContext, mp, "modelParam", true);

        assertNotNull(resolver.constructor);
    }

    @Test
    void initialConstructor_noDefaultConstructor_throwsException() throws Exception {
        Method method = getClass().getMethod("noDefaultCtorParam", NoDefaultCtorBean.class);
        MethodParameter mp = new MethodParameter(method, 0);

        assertThrows(IllegalStateException.class, () ->
                new ModelAttributeResolver(binderFactory, webContext, mappingContext, mp, "bean", true));
    }

    @Test
    void resolveArgument_createsAttributeAndBinds() throws Exception {
        stubRequestContext();
        Method method = getClass().getMethod("modelParam", ModelAttrBean.class);
        MethodParameter mp = new MethodParameter(method, 0);

        when(binderFactory.createBinder(any(NativeWebRequest.class), any(), eq("modelParam")))
                .thenReturn(dataBinder);
        when(dataBinder.getTarget()).thenReturn(new ModelAttrBean());

        ModelAttributeResolver resolver = new ModelAttributeResolver(
                binderFactory, webContext, mappingContext, mp, "modelParam", true);

        Object result = resolver.resolveArgument(request, response);
        assertNotNull(result);
        assertInstanceOf(ModelAttrBean.class, result);
    }

    @Test
    void resolveArgument_bindingDisabled_skipsBind() throws Exception {
        stubRequestContext();
        Method method = getClass().getMethod("modelParam", ModelAttrBean.class);
        MethodParameter mp = new MethodParameter(method, 0);

        when(binderFactory.createBinder(any(NativeWebRequest.class), any(), eq("modelParam")))
                .thenReturn(dataBinder);
        when(dataBinder.getTarget()).thenReturn(new ModelAttrBean());

        ModelAttributeResolver resolver = new ModelAttributeResolver(
                binderFactory, webContext, mappingContext, mp, "modelParam", false);

        Object result = resolver.resolveArgument(request, response);
        assertNotNull(result);
        assertInstanceOf(ModelAttrBean.class, result);
    }

    @SuppressWarnings("unused")
    public void modelParam(ModelAttrBean bean) {}

    @SuppressWarnings("unused")
    public void noDefaultCtorParam(NoDefaultCtorBean bean) {}

    public static class ModelAttrBean {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class NoDefaultCtorBean {
        @SuppressWarnings("unused")
        public NoDefaultCtorBean(String name) {}
    }
}
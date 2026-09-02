package io.springperf.web.core.arg.provider;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.MethodArgContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.ui.ModelMap;
import org.springframework.validation.BindingResult;
import org.springframework.validation.MapBindingResult;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.support.DefaultSessionAttributeStore;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.ModelFactory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验证 {@link ModelArgumentResolverProvider} 的 Model 初始化 5 步合并逻辑：
 * ① @ControllerAdvice @ModelAttribute ② 局部 @ModelAttribute ③ @ModelAttribute 参数
 * ④ @PathVariable ⑤ BindingResult。
 */
class ModelArgumentResolverProviderTest {

    @ControllerAdvice
    static class GlobalAdvice {
        @ModelAttribute("globalAttr")
        public String globalAttr() {
            return "global-value";
        }

        @ModelAttribute
        public void voidModel(Model model) {
            model.addAttribute("voidAttr", "void-value");
        }
    }

    static class TestController {
        @ModelAttribute("localAttr")
        public String localAttr() {
            return "local-value";
        }

        @RequestMapping("/test")
        public void handler(@ModelAttribute("user") User user) {
        }

        public void modelParam(Model model) {
        }

        public void modelMapParam(ModelMap model) {
        }

        public void extendedParam(ExtendedModelMap model) {
        }
    }

    static class NoModelAttrController {
        @RequestMapping("/test")
        public void handler(@ModelAttribute("user") User user) {
        }
    }

    public static class User {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Configuration
    static class Config {
        @Bean
        public GlobalAdvice globalAdvice() {
            return new GlobalAdvice();
        }
    }

    private AnnotationConfigApplicationContext ctx;
    private WebContext webContext;
    private ModelArgumentResolverProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        ctx = new AnnotationConfigApplicationContext(Config.class);
        webContext = mock(WebContext.class);
        when(webContext.getCtx()).thenReturn(ctx);
        provider = new ModelArgumentResolverProvider();
        provider.initWithWebContext(webContext);
    }

    private WebServerHttpRequest mockRequest() {
        WebServerHttpRequest request = mock(WebServerHttpRequest.class);
        RequestContext requestContext = mock(RequestContext.class);
        when(request.getRequestContext()).thenReturn(requestContext);
        Map<RequestAttribute<?>, Object> fastAttrs = new HashMap<>();
        Map<String, Object> stringAttrs = new HashMap<>();
        when(requestContext.getAttribute(any(RequestAttribute.class)))
                .thenAnswer(inv -> fastAttrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            fastAttrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(requestContext).setAttribute(any(RequestAttribute.class), any());
        when(requestContext.getAttributes()).thenReturn(stringAttrs);
        return request;
    }

    private MappingHandlerMethod mappingFor(Object bean, String methodName, Class<?>... params) throws Exception {
        HandlerMethod hm = new HandlerMethod(bean, bean.getClass().getMethod(methodName, params));
        return new MappingHandlerMethod(hm);
    }

    @Test
    void supports_modelTypes_true() throws Exception {
        // Model 参数
        assertTrue(provider.supports(new MethodParameter(TestController.class.getMethod("modelParam", Model.class), 0), null));
        // ModelMap 参数
        assertTrue(provider.supports(new MethodParameter(TestController.class.getMethod("modelMapParam", ModelMap.class), 0), null));
        // ExtendedModelMap 参数（ModelMap 子类）
        assertTrue(provider.supports(new MethodParameter(TestController.class.getMethod("extendedParam", ExtendedModelMap.class), 0), null));
    }

    @Test
    void supports_nonModel_false() throws Exception {
        MethodParameter p = mock(MethodParameter.class);
        when(p.getParameterType()).thenReturn((Class) String.class);
        assertFalse(provider.supports(p, null));
    }

    @Test
    void postProcess_mergesAdviceAndLocalModelAttributes() throws Exception {
        TestController bean = new TestController();
        MappingHandlerMethod mapping = mappingFor(bean, "handler", User.class);
        StaticArgumentResolver resolver = provider.getResolver(
                new MethodParameter(TestController.class.getMethod("handler", User.class), -1), mapping, webContext);

        WebServerHttpRequest request = mockRequest();
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        ModelMap model = (ModelMap) resolver.resolveArgument(request, response);

        MethodArgContext[] contexts = new MethodArgContext[0];
        Object[] args = new Object[]{model};
        resolver.postProcess(args, contexts, 0, request, response);

        // ① @ControllerAdvice @ModelAttribute 返回值
        assertEquals("global-value", model.get("globalAttr"));
        // ① void @ModelAttribute(Model) 写入
        assertEquals("void-value", model.get("voidAttr"));
        // ② 局部 @ModelAttribute 返回值
        assertEquals("local-value", model.get("localAttr"));
    }

    @Test
    void postProcess_mergesModelAttributeParameter() throws Exception {
        NoModelAttrController bean = new NoModelAttrController();
        MappingHandlerMethod mapping = mappingFor(bean, "handler", User.class);
        // model 参数 index=0，@ModelAttribute User 参数 index=1
        MethodParameter modelParam = new MethodParameter(NoModelAttrController.class.getMethod("handler", User.class), -1);
        MethodParameter userParam = new MethodParameter(NoModelAttrController.class.getMethod("handler", User.class), 0);
        StaticArgumentResolver resolver = provider.getResolver(modelParam, mapping, webContext);

        WebServerHttpRequest request = mockRequest();
        WebServerHttpResponse response = mock(WebServerHttpResponse.class);
        ModelMap model = (ModelMap) resolver.resolveArgument(request, response);

        // 构造两个 contexts：index=0 model 参数，index=1 @ModelAttribute User 参数
        MethodArgContext ctx0 = mock(MethodArgContext.class);
        when(ctx0.getMethodParameter()).thenReturn(modelParam);
        MethodArgContext ctx1 = mock(MethodArgContext.class);
        when(ctx1.getMethodParameter()).thenReturn(userParam);
        MethodArgContext[] contexts = new MethodArgContext[]{ctx0, ctx1};
        Object[] realArgs = new Object[]{model, new User()};
        resolver.postProcess(realArgs, contexts, 0, request, response);

        // ③ @ModelAttribute 参数（index=1）应合并到 model（key=user，值为 User 实例）
        assertTrue(model.get("user") instanceof User,
                "@ModelAttribute 参数应合并到 model，实际: " + model.get("user"));
        // 自身 index=0 不应被重复合并
        assertSame(model, realArgs[0]);
    }

    @Test
    void getOrder_highPrecedence() {
        assertEquals(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 100, provider.getOrder());
    }

    @Test
    void noAdviceMethods_returnsEmptyResolver() throws Exception {
        // 无 @ControllerAdvice 的上下文
        AnnotationConfigApplicationContext empty = new AnnotationConfigApplicationContext();
        empty.refresh();
        WebContext wc = mock(WebContext.class);
        when(wc.getCtx()).thenReturn(empty);
        ModelArgumentResolverProvider p = new ModelArgumentResolverProvider();
        p.initWithWebContext(wc);

        NoModelAttrController bean = new NoModelAttrController();
        MappingHandlerMethod mapping = mappingFor(bean, "handler", User.class);
        MethodParameter param = new MethodParameter(NoModelAttrController.class.getMethod("handler", User.class), 0);
        StaticArgumentResolver resolver = p.getResolver(param, mapping, wc);
        assertNotNull(resolver);
        WebServerHttpRequest request = mockRequest();
        ModelMap model = (ModelMap) resolver.resolveArgument(request, mock(WebServerHttpResponse.class));
        assertNotNull(model);
    }
}

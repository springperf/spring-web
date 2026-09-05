package io.springperf.web.autoconfigure.support;

import org.junit.jupiter.api.Test;
import org.springframework.aot.generate.GenerationContext;
import org.springframework.aot.hint.ExecutableHint;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeHint;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验证 {@link ControllerBeanFactoryInitializationAotProcessor}：
 * 为 {@code @Controller} 处理方法与 {@code @ControllerAdvice} 反射调用方法注册反射 hint、
 * 为 DTO（含泛型参数）注册绑定/序列化 hint。
 * JVM 模式下处理器由 Spring AOT 构建期调用，本测试直接驱动它验证注册结果。
 */
class ControllerBeanFactoryInitializationAotProcessorTest {

    private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

    @Test
    void processAheadOfTime_noControllers_returnsNull() {
        BeanFactoryInitializationAotContribution contribution =
                new ControllerBeanFactoryInitializationAotProcessor().processAheadOfTime(beanFactory);
        assertNull(contribution);
    }

    @Test
    void registersControllerAndDtoHints() {
        beanFactory.registerSingleton("testController", new TestController());

        BeanFactoryInitializationAotContribution contribution =
                new ControllerBeanFactoryInitializationAotProcessor().processAheadOfTime(beanFactory);
        assertNotNull(contribution, "存在 @Controller bean 时应产生 AOT contribution");

        RuntimeHints hints = new RuntimeHints();
        GenerationContext generationContext = mock(GenerationContext.class);
        when(generationContext.getRuntimeHints()).thenReturn(hints);
        contribution.applyTo(generationContext, null);

        // 控制器类注册反射 hint
        TypeHint controllerHint = hints.reflection().getTypeHint(TestController.class);
        assertNotNull(controllerHint, "控制器类应注册反射 hint");
        assertTrue(controllerHint.getMemberCategories().stream()
                        .anyMatch(c -> c.name().contains("INVOKE")),
                "控制器类应注册 INVOKE 成员类别");

        // 处理方法注册 INVOKE hint（hello 方法：@GetMapping，返回 User）
        assertMethodInvokeHint(hints, "hello");
        assertMethodInvokeHint(hints, "create");

        // DTO 类型（含 ResponseEntity<Map<String, Order>> 的泛型参数）注册反射 hint
        assertTrue(hints.reflection().getTypeHint(User.class) != null,
                "User DTO 应注册反射 hint");
        assertTrue(hints.reflection().getTypeHint(User.class).getMemberCategories()
                        .contains(MemberCategory.DECLARED_FIELDS),
                "User DTO 应注册 DECLARED_FIELDS 成员类别");
        assertTrue(hints.reflection().getTypeHint(Order.class) != null,
                "泛型参数 Order 应注册反射 hint");
        assertTrue(hints.reflection().getTypeHint(Order.class).getMemberCategories()
                        .contains(MemberCategory.DECLARED_FIELDS),
                "泛型参数 Order 应注册 DECLARED_FIELDS 成员类别");
    }

    @Test
    void skipsFrameworkTypes() {
        // @RequestMapping 返回 ResponseEntity（框架类型）但泛型 User 应被收集
        beanFactory.registerSingleton("frameworkReturnController", new FrameworkReturnController());

        BeanFactoryInitializationAotContribution contribution =
                new ControllerBeanFactoryInitializationAotProcessor().processAheadOfTime(beanFactory);
        assertNotNull(contribution);

        RuntimeHints hints = new RuntimeHints();
        GenerationContext generationContext = mock(GenerationContext.class);
        when(generationContext.getRuntimeHints()).thenReturn(hints);
        contribution.applyTo(generationContext, null);

        assertTrue(hints.reflection().getTypeHint(User.class) != null,
                "ResponseEntity<User> 的泛型参数 User 应被收集");
    }

    @Test
    void registersControllerAdviceMethodsAndDtos() {
        beanFactory.registerSingleton("testAdvice", new TestAdvice());

        BeanFactoryInitializationAotContribution contribution =
                new ControllerBeanFactoryInitializationAotProcessor().processAheadOfTime(beanFactory);
        assertNotNull(contribution, "存在 @ControllerAdvice bean 时应产生 AOT contribution");

        RuntimeHints hints = new RuntimeHints();
        GenerationContext generationContext = mock(GenerationContext.class);
        when(generationContext.getRuntimeHints()).thenReturn(hints);
        contribution.applyTo(generationContext, null);

        // @ExceptionHandler 方法注册 INVOKE hint
        assertMethodInvokeHint(hints, "handleIllegalArgument");
        // @InitBinder / @ModelAttribute 方法注册 INVOKE hint
        assertMethodInvokeHint(hints, "initBinder");
        assertMethodInvokeHint(hints, "addGlobalAttribute");

        // @ExceptionHandler 返回的 ResponseEntity<ErrorBody> 泛型参数 ErrorBody 注册字段 hint
        assertTrue(hints.reflection().getTypeHint(ErrorBody.class) != null,
                "advice DTO ErrorBody 应注册反射 hint");
        assertTrue(hints.reflection().getTypeHint(ErrorBody.class).getMemberCategories()
                        .contains(MemberCategory.DECLARED_FIELDS),
                "advice DTO ErrorBody 应注册 DECLARED_FIELDS 成员类别");
    }

    private static void assertMethodInvokeHint(RuntimeHints hints, String methodName) {
        boolean registered = hints.reflection().typeHints()
                .flatMap(TypeHint::methods)
                .map(ExecutableHint::getName)
                .anyMatch(methodName::equals);
        assertTrue(registered, "处理方法 " + methodName + " 应注册反射 hint");
    }

    @Controller
    static class TestController {

        @GetMapping("/hello")
        @ResponseBody
        public User hello() {
            return new User();
        }

        @PostMapping("/users")
        public ResponseEntity<User> create(@RequestBody User user) {
            return ResponseEntity.ok(user);
        }

        @PostMapping("/orders")
        public ResponseEntity<Map<String, Order>> orders() {
            return ResponseEntity.ok(Map.of());
        }
    }

    @Test
    void resolvesGenericControllerTypeVariable() {
        // 泛型控制器 BaseController<T> 的 @RequestBody T 应解析为 User
        beanFactory.registerSingleton("userController", new GenericUserController());

        BeanFactoryInitializationAotContribution contribution =
                new ControllerBeanFactoryInitializationAotProcessor().processAheadOfTime(beanFactory);
        assertNotNull(contribution);

        RuntimeHints hints = new RuntimeHints();
        GenerationContext generationContext = mock(GenerationContext.class);
        when(generationContext.getRuntimeHints()).thenReturn(hints);
        contribution.applyTo(generationContext, null);

        assertTrue(hints.reflection().getTypeHint(User.class) != null,
                "泛型控制器 BaseController<User> 的 T 应解析为 User 并注册反射 hint");
    }

    @Controller
    static class GenericUserController extends BaseController<User> {
    }

    abstract static class BaseController<T> {

        @PostMapping
        @ResponseBody
        public T create(@RequestBody T body) {
            return body;
        }
    }

    @Controller
    static class FrameworkReturnController {

        @RequestMapping("/framework")
        @ResponseBody
        public ResponseEntity<User> framework() {
            return ResponseEntity.ok(new User());
        }
    }

    @ControllerAdvice
    static class TestAdvice {

        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<ErrorBody> handleIllegalArgument(IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ErrorBody(ex.getMessage()));
        }

        @InitBinder
        public void initBinder(org.springframework.web.bind.WebDataBinder binder) {
            binder.setAutoGrowNestedPaths(false);
        }

        @ModelAttribute("global")
        public GlobalAttribute addGlobalAttribute() {
            return new GlobalAttribute();
        }
    }

    @SuppressWarnings("unused")
    static class ErrorBody {
        private String message;

        public ErrorBody() {
        }

        public ErrorBody(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    @SuppressWarnings("unused")
    static class GlobalAttribute {
        private String code;
    }

    @SuppressWarnings("unused")
    static class User {
        private String name;
        private int age;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }
    }

    @SuppressWarnings("unused")
    static class Order {
        private String id;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }
}

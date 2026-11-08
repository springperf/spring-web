package io.springperf.web.autoconfigure.support;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.aot.generate.GenerationContext;
import org.springframework.aot.hint.ExecutableHint;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeHint;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** AOT hints 收集的边界：null bean 类型、框架/JDK 类型黑名单、泛型变量、空 DTO、advice 方法筛选。 */
class ControllerBeanAotProcessorEdgeTest {

    private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    private final ControllerBeanFactoryInitializationAotProcessor processor = new ControllerBeanFactoryInitializationAotProcessor();

    private static RuntimeHints apply(BeanFactoryInitializationAotContribution contribution) {
        RuntimeHints hints = new RuntimeHints();
        GenerationContext ctx = mock(GenerationContext.class);
        when(ctx.getRuntimeHints()).thenReturn(hints);
        contribution.applyTo(ctx, null);
        return hints;
    }

    private static boolean hasMethodHint(RuntimeHints hints, String name) {
        return hints.reflection().typeHints().flatMap(TypeHint::methods).map(ExecutableHint::getName)
                .anyMatch(name::equals);
    }

    @Test
    void nullBeanTypesAreSkippedForControllersAndAdvices() {
        ConfigurableListableBeanFactory bf = mock(ConfigurableListableBeanFactory.class);
        when(bf.getBeanNamesForAnnotation(Controller.class)).thenReturn(new String[] { "ghost" });
        when(bf.getBeanNamesForAnnotation(ControllerAdvice.class)).thenReturn(new String[] { "ghostAdvice" });
        doReturn(null).when(bf).getType(anyString());

        assertNull(processor.processAheadOfTime(bf), "两个循环都跳过 null 类型后不应产生 contribution");
    }

    @Test
    void frameworkAndJdkTypesAreFilteredFromDtos() {
        beanFactory.registerSingleton("filtering", new FilteringController());

        RuntimeHints hints = apply(processor.processAheadOfTime(beanFactory));

        assertNotNull(hints.reflection().getTypeHint(FilteringController.class));
        assertNull(hints.reflection().getTypeHint(String.class), "java.* 应被过滤");
        assertNull(hints.reflection().getTypeHint(Map.class), "java.util.* 应被过滤");
        assertNull(hints.reflection().getTypeHint(ResponseEntity.class), "org.springframework.* 应被过滤");
        assertNull(hints.reflection().getTypeHint(io.springperf.web.context.WebContext.class), "框架内部包应被精确过滤");
        assertNull(hints.reflection().getTypeHint(io.netty.buffer.ByteBuf.class), "io.netty.* 应被过滤");
    }

    @Test
    void resolvedTypeVariableRegistersConcreteDto() {
        beanFactory.registerSingleton("concrete", new ConcreteController());

        RuntimeHints hints = apply(processor.processAheadOfTime(beanFactory));

        TypeHint dto = hints.reflection().getTypeHint(EdgeDto.class);
        assertNotNull(dto, "泛型 T 应解析为实际 DTO 并注册 hint");
        assertTrue(dto.getMemberCategories().stream().anyMatch(c -> c.name().contains("FIELDS")));
    }

    @Test
    void unresolvedTypeVariableIsSkippedWithoutFailing() {
        beanFactory.registerSingleton("raw", new RawController<>());

        BeanFactoryInitializationAotContribution contribution = processor.processAheadOfTime(beanFactory);

        assertNotNull(contribution);
        assertNotNull(apply(contribution).reflection().getTypeHint(RawController.class));
        assertNull(apply(contribution).reflection().getTypeHint(EdgeDto.class));
    }

    @Test
    void emptyDtoTypesStillRegisterController() {
        beanFactory.registerSingleton("plain", new PlainController());

        RuntimeHints hints = apply(processor.processAheadOfTime(beanFactory));

        assertNotNull(hints.reflection().getTypeHint(PlainController.class));
        assertEquals(1, hints.reflection().typeHints().count(), "没有 DTO 时不应注册额外 TypeHint");
        assertTrue(hasMethodHint(hints, "ping"));
    }

    @Test
    void onlyAnnotatedAdviceMethodsGetInvokeHints() {
        beanFactory.registerSingleton("partialAdvice", new PartialAdvice());

        RuntimeHints hints = apply(processor.processAheadOfTime(beanFactory));

        assertTrue(hasMethodHint(hints, "handleIllegalArgument"));
        assertFalse(hasMethodHint(hints, "notAnAdviceMethod"), "无 advice 注解的方法不应注册反射调用 hint");
    }

    @Controller
    static class FilteringController {
        @GetMapping("/filter")
        public ResponseEntity<Map<String, String>> filter(String name, int count,
                io.springperf.web.context.WebContext webContext, io.netty.buffer.ByteBuf buf) {
            return null;
        }
    }

    @Controller
    static abstract class GenericController<T> {
        @PostMapping("/generic")
        public T create(@RequestBody T body) {
            return body;
        }
    }

    static class ConcreteController extends GenericController<EdgeDto> {
    }

    static class RawController<T> extends GenericController<T> {
    }

    static class EdgeDto {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @Controller
    static class PlainController {
        @GetMapping("/ping")
        public String ping() {
            return "pong";
        }
    }

    @ControllerAdvice
    static class PartialAdvice {
        @ExceptionHandler(IllegalArgumentException.class)
        public String handleIllegalArgument(IllegalArgumentException ex) {
            return "error";
        }

        public String notAnAdviceMethod() {
            return "ignored";
        }
    }
}

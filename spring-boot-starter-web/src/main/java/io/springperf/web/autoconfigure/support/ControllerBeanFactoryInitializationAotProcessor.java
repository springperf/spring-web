package io.springperf.web.autoconfigure.support;

import io.springperf.web.core.mapping.MappingRegistry;
import org.springframework.aot.generate.GenerationContext;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.aot.BeanFactoryInitializationCode;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * GraalVM native-image AOT 处理器：为框架自有 {@link MappingRegistry} 扫描到的
 * {@code @Controller} bean 及其处理方法、以及框架反射调用的 {@code @ControllerAdvice}
 * 方法注册可达性提示。
 *
 * <p><b>为什么需要它</b>：Spring Boot AOT 只为 Spring MVC 的
 * {@code RequestMappingHandlerMapping} 自动生成 controller 方法 hints；
 * 本框架用自有 {@link MappingRegistry}（{@code getBeansWithAnnotation(Controller.class)} +
 * {@link ReflectionUtils#getUniqueDeclaredMethods}）扫描控制器，Spring Boot AOT 无法感知，
 * 因此 {@code MethodHandle.unreflect(处理方法)}（{@code InvokableHandlerMethod}）与
 * Jackson 反序列化 DTO（{@code JacksonHttpBodyConverter}）在 native 下会缺提示。
 * 本处理器在 AOT 构建期补齐：方法反射（{@code INVOKE_*}）、DTO 绑定反射与序列化提示。
 *
 * <p>同时覆盖 {@code @ControllerAdvice}：框架经 {@code ControllerAdviceBean.findAnnotatedBeans}
 * 发现 advice 并反射调用其 {@code @ExceptionHandler} / {@code @InitBinder} /
 * {@code @ModelAttribute} 方法（{@code ExceptionHandlerExceptionResolver} /
 * {@code WebDataBinderRegistry} / {@code ModelArgumentResolverProvider}），
 * 这些方法及方法参数/返回 DTO 同样需要 hints。
 *
 * <p>通过 {@code META-INF/spring/aot.factories} 注册为
 * {@code org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor}，
 * 由 Spring Boot {@code process-aot} 构建期调用；JVM 运行时完全不触发，零影响。
 *
 * <p>与 {@link io.springperf.web.autoconfigure.SpringWebRuntimeHints} 的关系：
 * 前者是静态 registrar（事件路径/资源），后者按 BeanFactory 实际内容动态收集用户控制器/advice——
 * 二者互补，职责不重叠。
 */
public class ControllerBeanFactoryInitializationAotProcessor implements BeanFactoryInitializationAotProcessor {

    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
        Set<Class<?>> controllerClasses = new LinkedHashSet<>();
        Set<Method> handlerMethods = new LinkedHashSet<>();
        Set<Type> dtoTypes = new LinkedHashSet<>();

        String[] controllerNames = beanFactory.getBeanNamesForAnnotation(Controller.class);
        for (String beanName : controllerNames) {
            Class<?> beanType = beanFactory.getType(beanName);
            if (beanType == null) {
                continue;
            }
            Class<?> targetClass = ClassUtils.getUserClass(beanType);
            controllerClasses.add(targetClass);
            collectHandlerMethods(targetClass, handlerMethods, dtoTypes);
        }

        // @ControllerAdvice：ControllerAdviceBean.findAnnotatedBeans 等价于
        // getBeansWithAnnotation(ControllerAdvice.class)（@RestControllerAdvice 经元注解命中）。
        String[] adviceNames = beanFactory.getBeanNamesForAnnotation(ControllerAdvice.class);
        for (String beanName : adviceNames) {
            Class<?> beanType = beanFactory.getType(beanName);
            if (beanType == null) {
                continue;
            }
            Class<?> targetClass = ClassUtils.getUserClass(beanType);
            controllerClasses.add(targetClass);
            collectAdviceMethods(targetClass, handlerMethods, dtoTypes);
        }

        if (controllerClasses.isEmpty()) {
            return null;
        }
        return new Contribution(controllerClasses, handlerMethods, dtoTypes);
    }

    /**
     * 与 {@link MappingRegistry#initComponentPhase1} 同口径扫描处理方法：
     * 找 {@code @RequestMapping}（含 {@code @GetMapping} 等组合注解）方法，
     * 收集方法参数类型与返回类型（含泛型参数）作为 DTO 候选。
     */
    private static void collectHandlerMethods(Class<?> controllerClass,
                                              Set<Method> handlerMethods, Set<Type> dtoTypes) {
        Method[] methods = ReflectionUtils.getUniqueDeclaredMethods(controllerClass);
        for (Method method : methods) {
            if (AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class) == null) {
                continue;
            }
            handlerMethods.add(method);
            for (Type paramType : method.getGenericParameterTypes()) {
                collectDtoType(paramType, controllerClass, dtoTypes);
            }
            collectDtoType(method.getGenericReturnType(), controllerClass, dtoTypes);
        }
    }

    /**
     * 收集 {@code @ControllerAdvice} 上被框架反射调用的方法：
     * {@code @ExceptionHandler} / {@code @InitBinder} / {@code @ModelAttribute}，
     * 以及这些方法的参数/返回 DTO。
     */
    private static void collectAdviceMethods(Class<?> adviceClass,
                                             Set<Method> handlerMethods, Set<Type> dtoTypes) {
        Method[] methods = ReflectionUtils.getUniqueDeclaredMethods(adviceClass);
        for (Method method : methods) {
            boolean adviceMethod = AnnotatedElementUtils.findMergedAnnotation(method, ExceptionHandler.class) != null
                    || AnnotatedElementUtils.findMergedAnnotation(method, InitBinder.class) != null
                    || AnnotatedElementUtils.findMergedAnnotation(method, ModelAttribute.class) != null;
            if (!adviceMethod) {
                continue;
            }
            handlerMethods.add(method);
            for (Type paramType : method.getGenericParameterTypes()) {
                collectDtoType(paramType, adviceClass, dtoTypes);
            }
            collectDtoType(method.getGenericReturnType(), adviceClass, dtoTypes);
        }
    }

    /**
     * 收集 DTO 候选类型（非 JDK / 非框架基础类型），并展开泛型参数（如
     * {@code ResponseEntity<Map<String, User>>} → {@code User}）。
     * 注意：框架包装类型（{@code ResponseEntity}/{@code Map}）本身跳过，
     * 但其泛型参数仍需递归收集。
     *
     * <p>对 {@link java.lang.reflect.TypeVariable}（泛型控制器如
     * {@code BaseController<T>} 的 {@code @RequestBody T}）用
     * {@link GenericTypeResolver#resolveType} 结合所属类解析出实际类型。</p>
     */
    private static void collectDtoType(Type type, Class<?> contextClass, Set<Type> dtoTypes) {
        if (type == null) {
            return;
        }
        if (type instanceof java.lang.reflect.TypeVariable) {
            Type resolved = GenericTypeResolver.resolveType(type, contextClass);
            if (resolved != null && resolved != type) {
                collectDtoType(resolved, contextClass, dtoTypes);
            }
            return;
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) type;
            for (Type arg : pt.getActualTypeArguments()) {
                collectDtoType(arg, contextClass, dtoTypes);
            }
            return;
        }
        if (type instanceof Class) {
            Class<?> clazz = (Class<?>) type;
            if (isFrameworkOrJdkType(clazz)) {
                return;
            }
            dtoTypes.add(type);
        }
    }

    private static boolean isFrameworkOrJdkType(Class<?> clazz) {
        if (clazz.isPrimitive() || clazz.isArray()) {
            return true;
        }
        String name = clazz.getName();
        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("sun.")) {
            return true;
        }
        if (name.startsWith("org.springframework.") || name.startsWith("io.netty.")) {
            return true;
        }
        // 框架内部包精确黑名单（不能用 io.springperf.web. 前缀整体过滤——
        // 用户 DTO/控制器常位于同前缀包下，会被误判为框架类型）。
        // autoconfigure 包不列入：其下的控制器（如 OpenApiDocController）是真实处理器，
        // 需要注册 hints 而非过滤。
        return name.startsWith("io.springperf.web.context.")
                || name.startsWith("io.springperf.web.core.")
                || name.startsWith("io.springperf.web.server.")
                || name.startsWith("io.springperf.web.http.")
                || name.startsWith("io.springperf.web.json.")
                || name.startsWith("io.springperf.web.util.")
                || name.startsWith("io.springperf.web.annotation.");
    }

    /** AOT 贡献：在构建期把收集到的 hints 注册进 RuntimeHints */
    private static final class Contribution implements BeanFactoryInitializationAotContribution {

        private final Set<Class<?>> controllerClasses;
        private final Set<Method> handlerMethods;
        private final Set<Type> dtoTypes;

        Contribution(Set<Class<?>> controllerClasses, Set<Method> handlerMethods, Set<Type> dtoTypes) {
            this.controllerClasses = controllerClasses;
            this.handlerMethods = handlerMethods;
            this.dtoTypes = dtoTypes;
        }

        @Override
        public void applyTo(GenerationContext generationContext, BeanFactoryInitializationCode beanFactoryInitializationCode) {
            RuntimeHints hints = generationContext.getRuntimeHints();

            for (Class<?> controllerClass : controllerClasses) {
                hints.reflection().registerType(controllerClass,
                        MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                        MemberCategory.INVOKE_DECLARED_METHODS);
            }
            for (Method method : handlerMethods) {
                hints.reflection().registerMethod(method, ExecutableMode.INVOKE);
            }
            if (!dtoTypes.isEmpty()) {
                // 绑定反射（构造器/属性访问）——覆盖 @ModelAttribute/@RequestBody 绑定与 Jackson 读写
                new BindingReflectionHintsRegistrar()
                        .registerReflectionHints(hints.reflection(), dtoTypes.toArray(new Type[0]));
                // DTO 字段/方法反射（native 下 Jackson 读写字段需显式注册；
                // 注意不使用 serialization().registerType——那是 Java 序列化，仅对 Serializable 生效）
                for (Type dtoType : dtoTypes) {
                    if (dtoType instanceof Class) {
                        hints.reflection().registerType((Class<?>) dtoType,
                                MemberCategory.DECLARED_FIELDS,
                                MemberCategory.INVOKE_DECLARED_METHODS);
                    }
                }
            }
        }
    }
}
